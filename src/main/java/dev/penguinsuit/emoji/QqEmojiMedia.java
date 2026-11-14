package dev.penguinsuit.emoji;

import dev.penguinsuit.config.BridgeConfig;
import dev.penguinsuit.media.*;
import java.io.*;
import java.net.URI;
import java.util.zip.ZipInputStream;

/** Only catalogued Tencent assets. ZIP entries are read in memory, never extracted to disk. */
public final class QqEmojiMedia {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("Penguin suit");
    public static byte[] fetch(URI uri,MediaFetcher fetcher,BridgeConfig cfg,long limit)throws IOException {
        return fetch(uri,fetcher,cfg,limit,true,cfg.maxDimension);
    }
    public static byte[] fetch(URI uri,MediaFetcher fetcher,BridgeConfig cfg,long limit,boolean gifsAllowed,int gifDimension)throws IOException {
        var face=QqEmojiCatalog.bySource(uri);if(face==null)throw new MediaException("unknown_emoji_source");
        if(face.animationSource()!=null&&gifsAllowed){
            try{
                byte[] archive=fetcher.fetchCataloguedEmoji(face.animationSource(),cfg,limit);
                var converted=ApngGif.convert(unpack(archive,face.animationEntry(),limit),cfg,Math.min(cfg.maxDimension,gifDimension),limit);
                if(dev.penguinsuit.config.BridgeLog.detailed("emoji"))LOG.info("[emoji] id={} animation=gif frames={} bytes={}",face.id(),converted.frames(),converted.bytes().length);
                return converted.bytes();
            }catch(IOException|RuntimeException e){
                if(Thread.currentThread().isInterrupted())throw new MediaException("cancelled");
                LOG.warn("[emoji] id={} animation=static-fallback reason={}",face.id(),e instanceof MediaException?e.getMessage():e.getClass().getSimpleName());
            }
        }else if(face.animationSource()!=null&&dev.penguinsuit.config.BridgeLog.detailed("emoji"))LOG.info("[emoji] id={} animation=static-fallback reason=gif_disabled",face.id());
        byte[] data=fetcher.fetchCataloguedEmoji(face.source(),cfg,limit);
        return face.zipEntry().isEmpty()?data:unpack(data,face.zipEntry(),limit);
    }
    public static byte[] unpack(byte[] zip,String expected,long limit)throws IOException {
        if(zip.length<4||zip.length>limit||zip[0]!='P'||zip[1]!='K')throw new MediaException("emoji_invalid_archive");
        int entries=0;long expanded=0;byte[] selected=null;
        try(var input=new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;byte[] buffer=new byte[8192];
            while((entry=input.getNextEntry())!=null) {
                if(++entries>32||entry.getSize()>limit)throw new MediaException("emoji_archive_limit");
                boolean wanted=entry.getName().equals(expected)&&!entry.isDirectory();
                var output=wanted?new ByteArrayOutputStream():null;int size;
                while((size=input.read(buffer))!=-1) {
                    if(Thread.currentThread().isInterrupted())throw new MediaException("cancelled");
                    expanded+=size;if(expanded>limit)throw new MediaException("emoji_archive_limit");
                    if(wanted)output.write(buffer,0,size);
                }
                if(wanted){if(selected!=null)throw new MediaException("emoji_duplicate_entry");selected=output.toByteArray();}
            }
        }
        if(selected==null)throw new MediaException("emoji_asset_missing");
        return selected;
    }
    private QqEmojiMedia(){}
}
