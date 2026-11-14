package dev.penguinsuit.media;

import dev.penguinsuit.config.BridgeConfig;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

public final class MediaInspector {
    public record Info(int width, int height, int frames, boolean gif) {}

    public Info inspect(byte[] bytes, BridgeConfig cfg, boolean gifsAllowed, int gifMaxDimension) throws IOException {
        if (bytes.length < 10 || bytes.length > cfg.maxDownloadBytes) throw new MediaException("invalid_size");
        boolean gif = bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
        boolean png = (bytes[0] & 255) == 137 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        boolean jpeg = (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
        if (!gif && !png && !jpeg) throw new MediaException("unsupported_format");
        if (gif && !gifsAllowed) throw new MediaException("gif_disabled");
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new MediaException("invalid_image");
            var reader = readers.next();
            try {
                reader.setInput(stream);
                int width = gif ? (bytes[6] & 255) | ((bytes[7] & 255) << 8) : reader.getWidth(0);
                int height = gif ? (bytes[8] & 255) | ((bytes[9] & 255) << 8) : reader.getHeight(0);
                int maxDim = gif ? Math.min(cfg.maxDimension, gifMaxDimension) : cfg.maxDimension;
                checkDimensions(width, height, maxDim, cfg.maxPixels);
                int frames = gif ? reader.getNumImages(true) : 1;
                if (frames < 1 || frames > cfg.maxGifFrames) throw new MediaException("gif_frame_limit");
                if (gif && (long) width * height * frames > cfg.maxGifDecodedPixels)
                    throw new MediaException("gif_pixel_budget");
                for (int i = 0; i < frames; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new MediaException("cancelled");
                    checkDimensions(reader.getWidth(i), reader.getHeight(i), maxDim, cfg.maxPixels);
                    var decoded = reader.read(i);
                    if (decoded == null) throw new MediaException("invalid_frame");
                    decoded.flush();
                }
                return new Info(width, height, frames, gif);
            } finally { reader.dispose(); }
        }
    }
    private static void checkDimensions(int w, int h, int maxDim, long maxPixels) throws MediaException {
        if (w < 1 || h < 1 || w > maxDim || h > maxDim || (long) w * h > maxPixels)
            throw new MediaException("image_dimensions");
    }
}
