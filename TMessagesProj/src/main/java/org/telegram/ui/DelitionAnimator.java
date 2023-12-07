package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.opengl.GLUtils;
import android.os.Build;
import android.util.Pair;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;

import org.telegram.alexContest.AndroidUtilities;
import org.telegram.alexContest.R;
import org.telegram.alexContest.SharedConfig;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.RLottieDrawable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class DelitionAnimator extends TextureView {
    private AnimationThread thread;
    public DelitionAnimator(@NonNull Context context) {
        super(context);
        init();
    }
    private void init() {
        setSurfaceTextureListener(addSurfaceListener());
        setOpaque(false);
    }
    public void stopAnimation() {
        if (thread != null) {
            thread.stopAnimation();
        }
    }
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.LOLLIPOP)
    public static boolean checkVersion() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }
    public void animate(List<ChatMessageCell> cells) {
        Bitmap atlas = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        if (atlas == null) {
            return;
        }
        Canvas canvas = new Canvas(atlas);
        int[] myLocation = new int[2];
        getLocationOnScreen(myLocation);
        List<ViewFrame> frames = new ArrayList<>(cells.size());
        for (ChatMessageCell cell : cells) {
            int[] relativeLocation = getLocation(cell, myLocation);
            int y = relativeLocation[1];
            int x = relativeLocation[0];
            draw(canvas, cell, x, y);
            frames.add(new ViewFrame(new Point(x, y), new Point(cell.getWidth(), cell.getHeight())));
        }
        thread.scheduleAnimation(new AnimConfig(frames,atlas));
    }

    private void draw(Canvas canvas, ChatMessageCell chatMessageCell, int x, int y) {
        canvas.save();
        canvas.translate(x, y);
        if (chatMessageCell.drawBackgroundInParent()) {
            chatMessageCell.drawBackgroundInternal(canvas, true);
        }
        chatMessageCell.draw(canvas);
        canvas.restore();
    }

    private int[] getLocation(View view, int[] locations) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        location[0] -= locations[0];
        location[1] -= locations[1];
        return location;
    }

    private TextureView.SurfaceTextureListener addSurfaceListener() {
        return new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (thread != null) {
                    thread.updateSize(width, height);
                }
            }
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                thread = new AnimationThread(surface, getWidth(), getHeight());
                thread.start();
            }
            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (thread != null) {
                    thread.halt();
                    thread = null;
                }
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        };
    }

    private static class AnimConfig {
        @NonNull
        public final List<ViewFrame> frames;
        @NonNull
        public final Bitmap bitmap;
        private AnimConfig(@NonNull List<ViewFrame> frames,@NonNull Bitmap bitmap ) {
            this.frames = frames;
            this.bitmap = bitmap;
        }
    }

    private static class ViewFrame {
        @NonNull
        public final Point location;
        @NonNull
        public final Point size;

        private ViewFrame(@NonNull Point location, @NonNull Point size) {
            this.location = location;
            this.size = size;
        }
    }

    private static class AnimationThread extends Thread {
        private final Object lock = new Object();
        private final Object resizeLock = new Object();
        private volatile boolean running = true;
        private volatile boolean shouldStop = false;
        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;
        private final ConcurrentLinkedQueue<AnimConfig> animationQueue = new ConcurrentLinkedQueue<>();
        private final SurfaceTexture surfaceTexture;
        private static final int S_ART_FLOAT = 4;
        private static final double MIN_DELTA = 1.0 / AndroidUtilities.screenRefreshRate;
        private static final double MAX_DELTA = (1.0 / AndroidUtilities.screenRefreshRate) * 2f;
        private static final int ATTRIBUTES_VERTEX_COUNT = 9;
        private static final float UP_DISPERSION = 220;
        private static final float MAX_DISPERSION = 1500 ;
        private static final float MAX_DURATION = 2.2f;
        private static final float EASE_DURATION = 1.2f;
        private static final float MIN_DURATION = 1.0f;
        private static final float FULL_DURATION = EASE_DURATION + MAX_DURATION;
        private int deltaTimeUniformHandle = 0;
        private int timeUniformHandle = 0;
        private int textureUniformHandle = 0;
        private int sizeUniformHandle = 0;
        private int pointSizeUniformHandle = 0;
        private int accUniformHandle = 0;
        private int maxDispersionUniformHandle = 0;
        private boolean resize;
        private int width, height;
        private int particlesCount;
        private final int particleSize = Math.max(1, AndroidUtilities.dp2(0.6f) * 2);
        private static final int maxChunkSize = AndroidUtilities.dp2(1f) * 2;
        private final Random randomizer = new Random();
        private List<ViewFrame> frames = new ArrayList<>(0);
        private float time = Float.MAX_VALUE;
        private final PointF pointSize = new PointF(0f, 0f);
        private int particleDims = particleSize;
        private int glProgram;
        private int maxPoints;
        private int textureId = 0;
        private int buffer = 0;
        private int[] particles;

        @Override
        public void run() {
            init();
            glInit();
            animate();
            clear();
        }
        public AnimationThread(SurfaceTexture surfaceTexture, int width, int height) {
            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
        }
        public void halt() {
            running = false;
        }
        public void stopAnimation() {
            shouldStop = true;
        }

        private void scheduleAnimation(AnimConfig config) {
            synchronized (lock) {
                animationQueue.add(config);
                lock.notifyAll();
            }
        }
        private void animate() {
            synchronized (lock) {
                boolean isAdjustmentPhase = false;
                int adjustmentFrameCount = 0;
                double lastGenerationDuration = 0.0;
                long lastTime = 0;
                while (running) {
                    if (shouldStop) {
                        time = Float.MAX_VALUE;
                    }
                    if (time > FULL_DURATION) {
                        GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
                        egl.eglSwapBuffers(eglDisplay, eglSurface);
                        while (animationQueue.isEmpty()) {
                            try {
                                lock.wait();
                            } catch (InterruptedException ignore) {}
                        }
                        shouldStop = false;
                    }
                    if (pollAnim()) {
                        time = 0f;
                        isAdjustmentPhase = true;
                        lastGenerationDuration = 0.0;
                        adjustmentFrameCount = 0;
                        lastTime = System.nanoTime();
                    }
                    final long timemark = System.nanoTime();
                    double deltaTime = (timemark - lastTime) / 1_000_000_000.;
                    lastTime = timemark;

                    if (deltaTime < MIN_DELTA) {
                        double wait = MIN_DELTA - deltaTime;
                        long milli = (long) (wait * 1000L);
                        int nano = (int) ((wait - milli / 1000.) * 1_000_000_000);
                        try {
                            lock.wait(milli, nano);
                        } catch (InterruptedException ignore) {}
                        deltaTime = MIN_DELTA;
                    } else if (isAdjustmentPhase) {
                        double adjustedForGeneration = deltaTime - lastGenerationDuration;
                        if (adjustedForGeneration > MAX_DELTA && particleDims < maxChunkSize) {
                            maxPoints = (int) (particlesCount / 1.5);
                            lastGenerationDuration = generateParticle(frames);
                            adjustmentFrameCount = 0;
                            time = 0f;
                        }
                    }
                    if (isAdjustmentPhase && adjustmentFrameCount++ > 10) {
                        lastGenerationDuration = 0.0;
                        isAdjustmentPhase = false;
                    }
                    time += deltaTime;
                    isResize();
                    frame((float) deltaTime);
                }
            }
        }
        public void updateSize(int width, int height) {
            synchronized (resizeLock) {
                resize = true;
                this.width = width;
                this.height = height;
            }
        }
        private void init() {
            switch (SharedConfig.getDevicePerformanceClass()) {
                case SharedConfig.PERFORMANCE_CLASS_HIGH:
                    maxPoints = 50000;
                    break;
                case SharedConfig.PERFORMANCE_CLASS_AVERAGE:
                    maxPoints = 25000;
                    break;
                default:
                case SharedConfig.PERFORMANCE_CLASS_LOW:
                    maxPoints = 10000;
                    break;
            }
        }
        private void glInit(){
            egl = (EGL10) EGLContext.getEGL();
            eglDisplay = egl.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == egl.EGL_NO_DISPLAY) {
                running = false;
                return;
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(eglDisplay, version)) {
                running = false;
                return;
            }

            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE
            };
            EGLConfig[] eglConfigs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!egl.eglChooseConfig(eglDisplay, configAttributes, eglConfigs, 1, numConfigs)) {
                running = false;
                return;
            }
            eglConfig = eglConfigs[0];
            int[] contextAttributes = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    EGL14.EGL_NONE
            };
            eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            if (eglSurface == null) {
                running = false;
                return;}
            eglContext = egl.eglCreateContext(eglDisplay, eglConfig, egl.EGL_NO_CONTEXT, contextAttributes);
            if (eglContext == null) {
                running = false;
                return;   }
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }
            int disintegrationFragShader = GLES31.glCreateShader(GLES31.GL_FRAGMENT_SHADER);
            int disintegrationShader = GLES31.glCreateShader(GLES31.GL_VERTEX_SHADER);
            if (disintegrationShader == 0 || disintegrationFragShader == 0) {
                running = false;
                return;}
            GLES31.glShaderSource(disintegrationShader, RLottieDrawable.readRes(null, R.raw.disintegration_shader) + "\n// " + Math.random());
            GLES31.glCompileShader(disintegrationShader);
            int[] state = new int[1];
            GLES31.glGetShaderiv(disintegrationShader, GLES31.GL_COMPILE_STATUS, state, 0);
            if (state[0] == 0) {
                GLES31.glDeleteShader(disintegrationShader);
                running = false;
                return;}
            GLES31.glShaderSource(disintegrationFragShader, RLottieDrawable.readRes(null, R.raw.disintegration_fragment_shader) + "\n// " + Math.random());
            GLES31.glCompileShader(disintegrationFragShader);
            GLES31.glGetShaderiv(disintegrationFragShader, GLES31.GL_COMPILE_STATUS, state, 0);
            if (state[0] == 0) {
                GLES31.glDeleteShader(disintegrationFragShader);
                running = false;
                return;}
            glProgram = GLES31.glCreateProgram();
            if (glProgram == 0) {
                running = false;
                return;}
            GLES31.glAttachShader(glProgram, disintegrationShader);
            GLES31.glAttachShader(glProgram, disintegrationFragShader);
            GLES31.glTransformFeedbackVaryings(glProgram, new String[]{
                    "oPos","oVelocity","oTexCord","oDuration","oX","oSeed"
            }, GLES31.GL_INTERLEAVED_ATTRIBS);
            GLES31.glLinkProgram(glProgram);
            GLES31.glGetProgramiv(glProgram, GLES31.GL_LINK_STATUS, state, 0);
            if (state[0] == 0) {
                running = false;
                return;}
            int[] textureState = new int[1];
            GLES20.glGenTextures(1, textureState, 0);
            textureId = textureState[0];
            GLES31.glViewport(0, 0, width, height);
            GLES31.glEnable(GLES31.GL_BLEND);
            GLES31.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES31.glUseProgram(glProgram);
            GLES31.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            textureUniformHandle = GLES31.glGetUniformLocation(glProgram, "textureHandle");
            deltaTimeUniformHandle = GLES31.glGetUniformLocation(glProgram, "deltaTimeHandle");
            timeUniformHandle = GLES31.glGetUniformLocation(glProgram, "time");
            pointSizeUniformHandle = GLES31.glGetUniformLocation(glProgram, "pointSizeHandle");
            sizeUniformHandle = GLES31.glGetUniformLocation(glProgram, "sizeHandle");
            maxDispersionUniformHandle = GLES31.glGetUniformLocation(glProgram, "maxDispersionHandle");
            accUniformHandle = GLES31.glGetUniformLocation(glProgram, "accHandle");
            GLES31.glUniform2f(maxDispersionUniformHandle, MAX_DISPERSION / width, MAX_DISPERSION / height);
            GLES31.glUniform1f(accUniformHandle, UP_DISPERSION / height);
            GLES31.glUniform1f(GLES31.glGetUniformLocation(glProgram, "easeDuration"),EASE_DURATION);
            GLES31.glUniform1f(GLES31.glGetUniformLocation(glProgram, "minDuration"),MIN_DURATION);
            GLES31.glUniform1f(GLES31.glGetUniformLocation(glProgram, "maxDuration"),MAX_DURATION);
            GLES31.glUniform1f(GLES31.glGetUniformLocation(glProgram, "particleSize"), particleSize);
        }
        private int bindAtr(int index, int size, int offset) {
            GLES31.glVertexAttribPointer(index, size, GLES31.GL_FLOAT, false, ATTRIBUTES_VERTEX_COUNT * S_ART_FLOAT, offset);
            GLES31.glEnableVertexAttribArray(index);
            GLES31.glVertexAttribDivisor(index, 1);
            return offset + size * S_ART_FLOAT;
        }
        private void frame(float deltaTime) {
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                running = false;
                return;
            }
            int offset = 0;
            int index = 0;
            GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT);
            GLES31.glUniform1f(pointSizeUniformHandle, particleDims);
            GLES31.glUniform1f(deltaTimeUniformHandle, deltaTime);
            GLES31.glUniform1f(timeUniformHandle, time);
            GLES31.glUniform2f(sizeUniformHandle, pointSize.x, pointSize.y);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particles[buffer]);
            offset = bindAtr(index++, 2, offset);
            offset = bindAtr(index++, 2, offset);
            offset = bindAtr(index++, 2, offset);
            offset = bindAtr(index++, 1, offset);
            offset = bindAtr(index++, 1, offset);
            offset = bindAtr(index++, 1, offset);
            GLES31.glBindBufferBase(GLES31.GL_TRANSFORM_FEEDBACK_BUFFER, 0, particles[1 - buffer]);
            int mode = GLES31.GL_POINTS;
            GLES31.glBeginTransformFeedback(mode);
            GLES31.glDrawArraysInstanced(mode, 0, 1, particlesCount);
            GLES31.glEndTransformFeedback();
            buffer = 1 - buffer;
            egl.eglSwapBuffers(eglDisplay, eglSurface);
        }
        private boolean pollAnim() {
            AnimConfig config = animationQueue.poll();
            if (config != null) {
                frames = config.frames;
                generateParticle(frames);
                Bitmap bitmap = config.bitmap;
                GLES31.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES31.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                int texture = 0;
                GLES31.glActiveTexture(GLES31.GL_TEXTURE0 + texture);
                GLES31.glBindTexture(GLES31.GL_TEXTURE_2D, textureId);
                GLES31.glUniform1i(textureUniformHandle, texture);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                bitmap.recycle();
                return true;
            }
            return false;
        }
        private void clear() {
            try {
                GLES31.glDeleteBuffers(2, particles, 0);
                GLES31.glDeleteProgram(glProgram);
                egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                egl.eglDestroySurface(eglDisplay, eglSurface);
                egl.eglDestroyContext(eglDisplay, eglContext);
            } catch (Exception e) {}
            particles = null;
            glProgram = 0;
            try {
                surfaceTexture.release();
            } catch (Exception e) {}
        }

        private void isResize() {
            synchronized (resizeLock) {
                if (resize) {
                    GLES31.glViewport(0, 0, width, height);
                    GLES31.glUniform2f(maxDispersionUniformHandle, MAX_DISPERSION / width, MAX_DISPERSION / height);
                    GLES31.glUniform1f(accUniformHandle, UP_DISPERSION / height);
                    resize = false;
                }
            }
        }
        private float toGlX(int x) {
            final float _x = x / (float) width;
            return (_x - 0.5f) * 2f;
        }
        private float toGlY(int y) {
            final float _y = y / (float) height;
            return (_y - 0.5f) * -2f;
        }
        private double generateParticle(List<ViewFrame> frames) {
            long now = System.currentTimeMillis();
            if (particles == null) {
                particles = new int[2];
                GLES31.glGenBuffers(2, particles, 0);
            }
            final FloatBuffer attrs = generateAttrs(frames);
            final int size = attrs.capacity() * S_ART_FLOAT;
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particles[0]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, size, attrs, GLES31.GL_DYNAMIC_DRAW);
            GLES31.glBindBuffer(GLES31.GL_ARRAY_BUFFER, particles[1]);
            GLES31.glBufferData(GLES31.GL_ARRAY_BUFFER, size, null, GLES31.GL_DYNAMIC_DRAW);
            buffer = 0;
            return (System.currentTimeMillis() - now) / 1000.0;
        }

        private FloatBuffer generateAttrs(List<ViewFrame> frames) {
            final Pair<Integer, Integer> countAndSize = calcParticle(frames);
            particlesCount = countAndSize.first;
            particleDims = countAndSize.second;
            pointSize.set(particleDims / (float) width, particleDims / (float) height);
            int size = particlesCount * ATTRIBUTES_VERTEX_COUNT;
            int i = 0;
            final int halfSize = particleDims / 2;
            final float[] attributes = new float[size];
            for (ViewFrame frame : frames) {
                final int frameTop = frame.location.y;
                final int frameBottom = frameTop + frame.size.y + halfSize;
                final int frameLeft = frame.location.x;
                final int frameRight = frameLeft + frame.size.x + halfSize;
                for (int y = frameTop + halfSize; y < frameBottom; y += particleDims) {
                    for (int x = frameLeft + halfSize; x < frameRight; x += particleDims) {
                        final float seed = randomizer.nextFloat();
                        i = vertex(attributes, i, x, y, seed);
                    }
                }
            }
            ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(attributes.length * S_ART_FLOAT);
            vertexByteBuffer.order(ByteOrder.nativeOrder());
            FloatBuffer vertexBuffer = vertexByteBuffer.asFloatBuffer();
            vertexBuffer.put(attributes);
            vertexBuffer.position(0);
            return vertexBuffer;
        }
        private int vertex(
                float[] vertices,
                int index,
                int x,
                int y,
                float seed
        ) {
            vertices[index++] = toGlX(x);
            vertices[index++] = toGlY(y);
            vertices[index++] = 0f;
            vertices[index++] = 0f;
            vertices[index++] = 0f;
            vertices[index++] = 0f;
            vertices[index++] = -1f;
            vertices[index++] = x / (float) width;
            vertices[index++] = seed;
            return index;
        }
        private Pair<Integer, Integer> calcParticle(List<ViewFrame> frames) {
            int count;
            int size = particleSize - 2;
            do {
                size += 2;
                count = 0;
                for (ViewFrame frame : frames) {
                    count += calcParticle(frame, size);
                }
            } while (count > maxPoints && size < maxChunkSize);
            return new Pair<>(count, size);
        }
        private static int calcParticle(ViewFrame frame, int particleSize) {
            int xCount = frame.size.x / particleSize;
            if (frame.size.x % particleSize != 0) {
                xCount++;
            }
            int yCount = frame.size.y / particleSize;
            if (frame.size.y % particleSize != 0) {
                yCount++;
            }
            return xCount * yCount;
        }
    }
}