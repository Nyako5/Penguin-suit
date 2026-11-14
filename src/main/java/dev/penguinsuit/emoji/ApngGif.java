package dev.penguinsuit.emoji;

import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.media.MediaException;
import java.awt.AlphaComposite;
import java.awt.image.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.*;

/** Bounded APNG decoder for catalogued assets. No native process, disk scratch or client changes. */
public final class ApngGif {
    private static final byte[] SIGNATURE={(byte)137,80,78,71,13,10,26,10};
    public record Result(byte[] bytes,int frames) {}
    private record Chunk(String type,byte[] data) {}
    private record Frame(int width,int height,int x,int y,int numerator,int denominator,int dispose,int blend,byte[] data) {}
    private record Animation(byte[] header,List<Chunk> palette,int width,int height,long plays,List<Frame> frames) {}

    public static Result convert(byte[] png,BridgeConfig cfg,int dimension,long byteLimit)throws IOException {
        long limit=Math.min(byteLimit,cfg.maxDownloadBytes);
        Animation a=parse(png,cfg,dimension,limit);
        var writers=ImageIO.getImageWritersByFormatName("gif");
        if(!writers.hasNext())throw new MediaException("emoji_gif_writer_missing");
        var writer=writers.next();
        try(var output=new LimitedImageOutput(limit)){
            writer.setOutput(output);writer.prepareWriteSequence(null);
            BufferedImage canvas=new BufferedImage(a.width(),a.height(),BufferedImage.TYPE_INT_ARGB);
            double elapsed=0;long encodedDelay=0;
            for(int i=0;i<a.frames().size();i++){
                interrupted();var f=a.frames().get(i);
                int[] previous=f.dispose()==2?canvas.getRGB(f.x(),f.y(),f.width(),f.height(),null,0,f.width()):null;
                BufferedImage layer=decode(a,f);
                var g=canvas.createGraphics();
                try{g.setComposite(f.blend()==0?AlphaComposite.Src:AlphaComposite.SrcOver);g.drawImage(layer,f.x(),f.y(),null);}
                finally{g.dispose();layer.flush();}
                BufferedImage indexed=palette(canvas);
                elapsed+=100.0*f.numerator()/(f.denominator()==0?100:f.denominator());
                // Pinned CR's decoder maps <2 centiseconds to 100ms. Keep fast frames at 20ms
                // rather than triggering that fallback; retain every source frame.
                long delay=Math.max(2,Math.round(elapsed)-encodedDelay);
                if(delay>65535)throw new MediaException("emoji_animation_delay_limit");
                encodedDelay+=delay;
                var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(indexed),null);
                var root=(IIOMetadataNode)metadata.getAsTree("javax_imageio_gif_image_1.0");
                var control=node(root,"GraphicControlExtension");
                control.setAttribute("disposalMethod","restoreToBackgroundColor");
                control.setAttribute("userInputFlag","FALSE");control.setAttribute("transparentColorFlag","TRUE");
                control.setAttribute("delayTime",Long.toString(delay));control.setAttribute("transparentColorIndex","0");
                if(i==0&&a.plays()!=1){
                    // NETSCAPE counts repetitions after the first play; APNG counts total plays.
                    if(a.plays()>65536)throw new MediaException("emoji_animation_loop_limit");
                    int loops=a.plays()==0?0:(int)a.plays()-1;
                    var ext=new IIOMetadataNode("ApplicationExtension");ext.setAttribute("applicationID","NETSCAPE");ext.setAttribute("authenticationCode","2.0");
                    ext.setUserObject(new byte[]{1,(byte)loops,(byte)(loops>>>8)});node(root,"ApplicationExtensions").appendChild(ext);
                }
                metadata.setFromTree("javax_imageio_gif_image_1.0",root);
                writer.writeToSequence(new IIOImage(indexed,null,metadata),null);indexed.flush();
                if(f.dispose()==1||(f.dispose()==2&&i==0)){
                    g=canvas.createGraphics();try{g.setComposite(AlphaComposite.Clear);g.fillRect(f.x(),f.y(),f.width(),f.height());}finally{g.dispose();}
                }else if(f.dispose()==2)canvas.setRGB(f.x(),f.y(),f.width(),f.height(),previous,0,f.width());
            }
            canvas.flush();writer.endWriteSequence();return new Result(output.bytes(),a.frames().size());
        }catch(IIOException e){if(e.getCause() instanceof MediaException reason)throw reason;throw e;}
        finally{writer.dispose();}
    }
    private static Animation parse(byte[] data,BridgeConfig cfg,int dimension,long limit)throws IOException {
        if(data.length<33||data.length>limit||!Arrays.equals(SIGNATURE,Arrays.copyOf(data,8)))throw new MediaException("emoji_invalid_apng");
        byte[] header=null;List<Chunk> palette=new ArrayList<>();List<Frame> frames=new ArrayList<>();
        int width=0,height=0,expected=0,sequence=0,position=8,chunks=0;long plays=0;
        boolean imageData=false,ended=false,defaultIncluded=false;Frame current=null;ByteArrayOutputStream compressed=null;
        while(position<data.length){
            interrupted();if(++chunks>8192||position+12L>data.length)throw new MediaException("emoji_apng_chunks");
            int size=integer(data,position);if(size<0||position+12L+size>data.length)throw new MediaException("emoji_apng_chunk_size");
            String type=new String(data,position+4,4,StandardCharsets.US_ASCII);
            var crc=new CRC32();crc.update(data,position+4,size+4);
            if(crc.getValue()!=Integer.toUnsignedLong(integer(data,position+8+size)))throw new MediaException("emoji_apng_crc");
            int start=position+8;position+=size+12;
            if(header==null&&!type.equals("IHDR"))throw new MediaException("emoji_apng_header");
            switch(type){
                case "IHDR" -> {
                    if(header!=null||size!=13)throw new MediaException("emoji_apng_header");
                    header=Arrays.copyOfRange(data,start,start+size);width=integer(header,0);height=integer(header,4);
                    if(width<1||height<1||width>dimension||height>dimension||(long)width*height>cfg.maxPixels)throw new MediaException("emoji_animation_dimensions");
                }
                case "acTL" -> {
                    if(size!=8||expected!=0||imageData)throw new MediaException("emoji_apng_control");
                    expected=integer(data,start);plays=Integer.toUnsignedLong(integer(data,start+4));
                    if(expected<1||expected>cfg.maxGifFrames||(long)width*height*expected>cfg.maxGifDecodedPixels)throw new MediaException("emoji_animation_frame_budget");
                }
                case "fcTL" -> {
                    if(expected==0||size!=26||integer(data,start)!=sequence++)throw new MediaException("emoji_apng_sequence");
                    if(current!=null)frames.add(finish(current,compressed));
                    if(frames.size()>=expected)throw new MediaException("emoji_apng_frame_count");
                    var b=ByteBuffer.wrap(data,start+4,22);int w=b.getInt(),h=b.getInt(),x=b.getInt(),y=b.getInt();
                    int num=Short.toUnsignedInt(b.getShort()),den=Short.toUnsignedInt(b.getShort()),dispose=Byte.toUnsignedInt(b.get()),blend=Byte.toUnsignedInt(b.get());
                    if(w<1||h<1||x<0||y<0||(long)x+w>width||(long)y+h>height||dispose>2||blend>1)throw new MediaException("emoji_apng_frame_bounds");
                    if(!imageData){if(x!=0||y!=0||w!=width||h!=height||current!=null)throw new MediaException("emoji_apng_default_frame");defaultIncluded=true;}
                    current=new Frame(w,h,x,y,num,den,dispose,blend,null);compressed=new ByteArrayOutputStream();
                }
                case "IDAT" -> {
                    if(expected==0||(current!=null&&(!defaultIncluded||!frames.isEmpty())))throw new MediaException("emoji_apng_data_order");
                    imageData=true;if(current!=null)compressed.write(data,start,size);
                }
                case "fdAT" -> {
                    if(!imageData||current==null||size<4||integer(data,start)!=sequence++||(defaultIncluded&&frames.isEmpty()))throw new MediaException("emoji_apng_sequence");
                    compressed.write(data,start+4,size-4);
                }
                case "PLTE","tRNS" -> {
                    if(imageData||palette.stream().anyMatch(c->c.type().equals(type))||size>768)throw new MediaException("emoji_apng_palette");
                    palette.add(new Chunk(type,Arrays.copyOfRange(data,start,start+size)));
                }
                case "IEND" -> {
                    if(size!=0||current==null||position!=data.length)throw new MediaException("emoji_apng_end");
                    frames.add(finish(current,compressed));ended=true;
                }
                default -> {if((type.charAt(0)&32)==0)throw new MediaException("emoji_apng_unknown_critical_chunk");}
            }
        }
        if(!ended||frames.size()!=expected)throw new MediaException("emoji_apng_frame_count");
        return new Animation(header,List.copyOf(palette),width,height,plays,List.copyOf(frames));
    }
    private static Frame finish(Frame f,ByteArrayOutputStream bytes)throws MediaException {
        if(bytes==null||bytes.size()==0)throw new MediaException("emoji_apng_empty_frame");
        return new Frame(f.width(),f.height(),f.x(),f.y(),f.numerator(),f.denominator(),f.dispose(),f.blend(),bytes.toByteArray());
    }
    private static BufferedImage decode(Animation a,Frame f)throws IOException {
        var png=new ByteArrayOutputStream();png.write(SIGNATURE);byte[] header=a.header().clone();
        ByteBuffer.wrap(header).putInt(f.width()).putInt(f.height());chunk(png,"IHDR",header);
        for(var c:a.palette())chunk(png,c.type(),c.data());chunk(png,"IDAT",f.data());chunk(png,"IEND",new byte[0]);
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(png.toByteArray()))){
            var readers=ImageIO.getImageReadersByFormatName("png");if(!readers.hasNext())throw new MediaException("emoji_png_reader_missing");
            var reader=readers.next();try{
                reader.setInput(input,true,true);var image=reader.read(0);
                if(image==null||image.getWidth()!=f.width()||image.getHeight()!=f.height())throw new MediaException("emoji_apng_decode");return image;
            }finally{reader.dispose();}
        }
    }
    private static int integer(byte[] data,int offset){return ByteBuffer.wrap(data,offset,4).getInt();}
    private static void chunk(OutputStream target,String name,byte[] data)throws IOException {
        var out=new DataOutputStream(target);byte[] type=name.getBytes(StandardCharsets.US_ASCII);out.writeInt(data.length);out.write(type);out.write(data);
        var crc=new CRC32();crc.update(type);crc.update(data);out.writeInt((int)crc.getValue());
    }
    private static void interrupted()throws MediaException {if(Thread.currentThread().isInterrupted())throw new MediaException("cancelled");}
    private static IIOMetadataNode node(IIOMetadataNode root,String name){
        for(int i=0;i<root.getLength();i++)if(root.item(i).getNodeName().equals(name))return (IIOMetadataNode)root.item(i);
        var node=new IIOMetadataNode(name);root.appendChild(node);return node;
    }
    /** Adaptive 255-colour palette plus transparent index 0; alpha is reduced to GIF's one-bit transparency. */
    private static BufferedImage palette(BufferedImage image)throws IOException {
        int[] rgb=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
        int[] counts=new int[32768];long[] red=new long[32768],green=new long[32768],blue=new long[32768];
        for(int p:rgb)if((p>>>24)>=128){int k=key(p);counts[k]++;red[k]+=(p>>>16)&255;green[k]+=(p>>>8)&255;blue[k]+=p&255;}
        List<Integer> present=new ArrayList<>();for(int i=0;i<counts.length;i++)if(counts[i]>0)present.add(i);
        List<Box> boxes=new ArrayList<>();if(!present.isEmpty())boxes.add(new Box(present,counts));
        while(boxes.size()<255){
            interrupted();Box selected=null;
            for(var b:boxes)if(b.colors.size()>1&&(selected==null||b.score()>selected.score()))selected=b;
            if(selected==null)break;boxes.remove(selected);boxes.addAll(selected.split(counts));
        }
        byte[] r=new byte[256],g=new byte[256],b=new byte[256];int[] lookup=new int[32768];int index=1;
        for(var box:boxes){long rr=0,gg=0,bb=0,n=0;for(int k:box.colors){rr+=red[k];gg+=green[k];bb+=blue[k];n+=counts[k];lookup[k]=index;}
            r[index]=(byte)(rr/n);g[index]=(byte)(gg/n);b[index]=(byte)(bb/n);index++;}
        var model=new IndexColorModel(8,256,r,g,b,0);
        var result=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_BYTE_INDEXED,model);
        byte[] pixels=((DataBufferByte)result.getRaster().getDataBuffer()).getData();
        for(int i=0;i<rgb.length;i++)pixels[i]=(byte)((rgb[i]>>>24)<128?0:lookup[key(rgb[i])]);
        return result;
    }
    private static int key(int p){return ((p>>>19)&31)<<10|((p>>>11)&31)<<5|((p>>>3)&31);}
    private static final class Box {
        final List<Integer> colors;final int channel,range;final long population;
        Box(List<Integer> colors,int[] counts){
            this.colors=colors;int[] min={31,31,31},max={0,0,0};long n=0;
            for(int k:colors){n+=counts[k];for(int c=0;c<3;c++){int v=(k>>>(10-5*c))&31;min[c]=Math.min(min[c],v);max[c]=Math.max(max[c],v);}}
            int c=0;for(int i=1;i<3;i++)if(max[i]-min[i]>max[c]-min[c])c=i;
            channel=c;range=max[c]-min[c];population=n;
        }
        long score(){return population*range;}
        List<Box> split(int[] counts){
            colors.sort(Comparator.comparingInt(k->(k>>>(10-5*channel))&31));long n=0;int at=0;
            do{n+=counts[colors.get(at++)];}while(at<colors.size()-1&&n<population/2);
            return List.of(new Box(new ArrayList<>(colors.subList(0,at)),counts),new Box(new ArrayList<>(colors.subList(at,colors.size())),counts));
        }
    }
    /** Seekable bounded in-memory output: ImageIO cannot grow beyond the configured download/cache budget. */
    private static final class LimitedImageOutput extends ImageOutputStreamImpl {
        private byte[] buffer;private int size;private final int limit;
        LimitedImageOutput(long limit)throws MediaException{
            if(limit<1||limit>Integer.MAX_VALUE-8)throw new MediaException("emoji_gif_byte_limit");this.limit=(int)limit;buffer=new byte[Math.min(8192,this.limit)];
        }
        private void capacity(int n)throws IOException{
            if(streamPos+n>limit)throw new MediaException("emoji_gif_byte_limit");
            if(streamPos+n>buffer.length)buffer=Arrays.copyOf(buffer,(int)Math.min(limit,Math.max(streamPos+n,(long)buffer.length*2)));
        }
        @Override public int read(){bitOffset=0;return streamPos>=size?-1:buffer[(int)streamPos++]&255;}
        @Override public int read(byte[] b,int off,int len){bitOffset=0;if(len==0)return 0;if(streamPos>=size)return -1;int n=Math.min(len,size-(int)streamPos);System.arraycopy(buffer,(int)streamPos,b,off,n);streamPos+=n;return n;}
        @Override public void write(int value)throws IOException{flushBits();capacity(1);buffer[(int)streamPos++]=(byte)value;size=Math.max(size,(int)streamPos);}
        @Override public void write(byte[] b,int off,int len)throws IOException{flushBits();capacity(len);System.arraycopy(b,off,buffer,(int)streamPos,len);streamPos+=len;size=Math.max(size,(int)streamPos);}
        @Override public long length(){return size;}
        byte[] bytes(){return Arrays.copyOf(buffer,size);}
    }
    private ApngGif(){}
}
