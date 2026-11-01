package com.example.introvideo;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Програє PNG-кадри з .minecraft/config/introvideo/frames/frame_0001.png ...
 * Тримає в пам'яті лише кілька кадрів наперед, тому 30 с відео не з'їдає відеопам'ять.
 */
public final class VideoPlayer {
    public static final String MOD_ID = "introvideo";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Типова частота кадрів. Має збігатися з частотою, з якою ви рендерили кадри. */
    private static final int DEFAULT_FPS = 30;
    /** Можна змінити без перезбірки: config/introvideo/fps.txt (одне число). */
    private static int fps = DEFAULT_FPS;
    /** Скільки кадрів декодувати наперед. */
    private static final int PREFETCH = 4;

    private static final ResourceLocation TEXTURE_ID = ResourceLocation.fromNamespaceAndPath(MOD_ID, "video_frame");

    private static final Map<Integer, CompletableFuture<NativeImage>> PENDING = new HashMap<>();
    private static ExecutorService pool;

    private static Path framesDir;
    private static List<Path> frames;
    private static int frameCount;
    private static long startNanos = -1;
    private static boolean finished;

    private static DynamicTexture texture;
    private static int texW;
    private static int texH;

    private VideoPlayer() {
    }

    /** @return true, якщо відео ще грає і оригінальний екран треба пропустити. */
    public static boolean render(GuiGraphics graphics) {
        if (finished) {
            return false;
        }

        try {
            if (startNanos < 0) {
                if (!init()) {
                    finish();
                    return false;
                }
                startNanos = System.nanoTime();
            }

            int frame = (int) ((System.nanoTime() - startNanos) * fps / 1_000_000_000L);
            if (frame >= frameCount) {
                finish();
                return false;
            }

            prefetch(frame);
            updateTexture(frame);

            int w = graphics.guiWidth();
            int h = graphics.guiHeight();
            graphics.fill(0, 0, w, h, 0xFF000000);

            if (texture != null) {
                // Вписуємо кадр у екран, зберігаючи пропорції
                float scale = Math.min((float) w / texW, (float) h / texH);
                int drawW = Math.round(texW * scale);
                int drawH = Math.round(texH * scale);
                int x = (w - drawW) / 2;
                int y = (h - drawH) / 2;
                graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID,
                        x, y, 0f, 0f, drawW, drawH, texW, texH, texW, texH);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("Intro video failed, falling back to the normal loading screen", t);
            finish();
            return false;
        }
    }

    private static boolean init() throws Exception {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        framesDir = configDir.resolve("frames");
        Files.createDirectories(framesDir);

        Path fpsFile = configDir.resolve("fps.txt");
        if (Files.exists(fpsFile)) {
            try {
                int value = Integer.parseInt(Files.readString(fpsFile).trim());
                if (value >= 1 && value <= 240) {
                    fps = value;
                }
            } catch (NumberFormatException e) {
                LOGGER.warn("Invalid number in {}, using {} fps", fpsFile, DEFAULT_FPS);
            }
        }

        // Усі PNG з папки, у природному порядку (frame_2 перед frame_10)
        frames = new ArrayList<>();
        try (Stream<Path> files = Files.list(framesDir)) {
            files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(VideoPlayer::compareNatural)
                    .forEach(frames::add);
        }
        frameCount = frames.size();
        if (frameCount == 0) {
            LOGGER.warn("No PNG frames found in {}", framesDir);
            return false;
        }

        pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "introvideo-decoder");
            t.setDaemon(true);
            return t;
        });
        LOGGER.info("Playing intro video: {} frames at {} fps", frameCount, fps);
        return true;
    }

    private static int compareNatural(Path a, Path b) {
        String x = a.getFileName().toString();
        String y = b.getFileName().toString();
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i);
            char cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int si = i;
                int sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) {
                    i++;
                }
                while (j < y.length() && Character.isDigit(y.charAt(j))) {
                    j++;
                }
                int cmp = new BigInteger(x.substring(si, i)).compareTo(new BigInteger(y.substring(sj, j)));
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                if (cx != cy) {
                    return Character.compare(cx, cy);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(x.length() - i, y.length() - j);
    }

    private static void prefetch(int frame) {
        for (int i = frame; i < Math.min(frame + PREFETCH, frameCount); i++) {
            final int index = i;
            PENDING.computeIfAbsent(index, k -> CompletableFuture.supplyAsync(() -> {
                try (InputStream in = Files.newInputStream(frames.get(index))) {
                    return NativeImage.read(in);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, pool));
        }
    }

    private static void updateTexture(int frame) {
        CompletableFuture<NativeImage> current = PENDING.get(frame);
        if (current == null || !current.isDone() || current.isCompletedExceptionally()) {
            return; // кадр ще не готовий, показуємо попередній
        }

        NativeImage image = current.join();

        if (texture == null) {
            texW = image.getWidth();
            texH = image.getHeight();
            texture = new DynamicTexture(() -> MOD_ID + "_frame", image);
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
        } else {
            texture.getPixels().copyFrom(image);
            texture.upload();
            image.close();
        }
        PENDING.remove(frame);

        // Прибираємо кадри, які ми пропустили
        Iterator<Map.Entry<Integer, CompletableFuture<NativeImage>>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, CompletableFuture<NativeImage>> e = it.next();
            if (e.getKey() < frame) {
                CompletableFuture<NativeImage> f = e.getValue();
                if (f.isDone() && !f.isCompletedExceptionally()) {
                    f.join().close();
                }
                it.remove();
            }
        }
    }

    private static void finish() {
        finished = true;
        if (pool != null) {
            pool.shutdownNow();
        }
        for (CompletableFuture<NativeImage> f : PENDING.values()) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                f.join().close();
            }
        }
        PENDING.clear();
        if (texture != null) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
            texture = null;
        }
    }
}
