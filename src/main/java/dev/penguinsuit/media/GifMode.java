package dev.penguinsuit.media;

import dev.penguinsuit.config.BridgeConfig;
import java.io.*;
import javax.imageio.ImageIO;

/** Conversion is performed only after bounded full input validation. */
public final class GifMode {
    public static boolean isGif(byte[] b){return b.length>=6&&b[0]=='G'&&b[1]=='I'&&b[2]=='F';}
    public static byte[] apply(byte[] bytes,String mode,BridgeConfig config,long limit)throws IOException{
        if(!isGif(bytes)||mode.equals("ANIMATED"))return bytes;
        if(mode.equals("TEXT"))throw new MediaException("gif_text_mode");
        new MediaInspector().inspect(bytes,config,true,config.maxDimension);
        var image=ImageIO.read(new ByteArrayInputStream(bytes));if(image==null)throw new MediaException("invalid_frame");
        try {
            var output=new ByteArrayOutputStream();
            OutputStream bounded=new OutputStream(){public void write(int b)throws IOException{if(output.size()>=limit)throw new MediaException("static_image_too_large");output.write(b);}public void write(byte[] b,int o,int n)throws IOException{if((long)output.size()+n>limit)throw new MediaException("static_image_too_large");output.write(b,o,n);}};
            try { if(!ImageIO.write(image,"png",bounded))throw new MediaException("png_encoder_missing"); }
            catch(javax.imageio.IIOException e){for(Throwable cause=e.getCause();cause!=null;cause=cause.getCause())if(cause instanceof MediaException media)throw media;throw e;}
            return output.toByteArray();
        }finally{image.flush();}
    }
    private GifMode(){}
}
