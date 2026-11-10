#include "gaussian_blur.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#define TAG  "GPUBlur"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int MAX_HALF_KERNEL = 64;
static constexpr int WEIGHT_ARRAY_SIZE = MAX_HALF_KERNEL + 1; // 65

static const char *VERT_SRC = R"glsl(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord   = aTexCoord;
}
)glsl";

static const char *HBLUR_FRAG_SRC = R"glsl(#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform float     uWeights[65];
uniform int       uKernelRadius;
uniform float     uTexelWidth;
in  vec2 vTexCoord;
out vec4 fragColor;
void main() {
    vec4 result = texture(uTexture, vTexCoord) * uWeights[0];
    for (int i = 1; i < 65; i++) {
        if (i > uKernelRadius) break;
        float off = float(i) * uTexelWidth;
        result += texture(uTexture, vTexCoord + vec2( off, 0.0)) * uWeights[i];
        result += texture(uTexture, vTexCoord + vec2(-off, 0.0)) * uWeights[i];
    }
    fragColor = result;
}
)glsl";

static const char *VBLUR_FRAG_SRC = R"glsl(#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform float     uWeights[65];
uniform int       uKernelRadius;
uniform float     uTexelHeight;
in  vec2 vTexCoord;
out vec4 fragColor;
void main() {
    vec4 result = texture(uTexture, vTexCoord) * uWeights[0];
    for (int i = 1; i < 65; i++) {
        if (i > uKernelRadius) break;
        float off = float(i) * uTexelHeight;
        result += texture(uTexture, vTexCoord + vec2(0.0,  off)) * uWeights[i];
        result += texture(uTexture, vTexCoord + vec2(0.0, -off)) * uWeights[i];
    }
    fragColor = result;
}
)glsl";

static const char *COPY_FRAG_SRC = R"glsl(#version 300 es
precision mediump float;
uniform sampler2D uTexture;
in  vec2 vTexCoord;
out vec4 fragColor;
void main() {
    fragColor = texture(uTexture, vTexCoord);
}
)glsl";

static const float QUAD_VERTS[] = {
        -1.0f, -1.0f, 0.0f, 0.0f,
        1.0f, -1.0f, 1.0f, 0.0f,
        1.0f, 1.0f, 1.0f, 1.0f,
        -1.0f, -1.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 1.0f, 1.0f,
        -1.0f, 1.0f, 0.0f, 1.0f,
};

struct GlResources {
    GLuint hblurProg = 0, vblurProg = 0, copyProg = 0;
    GLuint vao = 0, vbo = 0;
    GLuint srcTex = 0;
    GLuint downTex = 0, downFbo = 0;
    GLuint tmpTex = 0, tmpFbo = 0;
    GLuint blurTex = 0, blurFbo = 0;
    GLuint upTex = 0, upFbo = 0;

    ~GlResources() {
        if (vao) {
            glDeleteVertexArrays(1, &vao);
            glDeleteBuffers(1, &vbo);
        }
        if (srcTex) glDeleteTextures(1, &srcTex);
        if (downTex) {
            glDeleteTextures(1, &downTex);
            glDeleteFramebuffers(1, &downFbo);
        }
        if (tmpTex) {
            glDeleteTextures(1, &tmpTex);
            glDeleteFramebuffers(1, &tmpFbo);
        }
        if (blurTex) {
            glDeleteTextures(1, &blurTex);
            glDeleteFramebuffers(1, &blurFbo);
        }
        if (upTex) {
            glDeleteTextures(1, &upTex);
            glDeleteFramebuffers(1, &upFbo);
        }
        if (hblurProg) glDeleteProgram(hblurProg);
        if (vblurProg) glDeleteProgram(vblurProg);
        if (copyProg) glDeleteProgram(copyProg);
    }
};

/**
 * Owns the per-call EGL context and pbuffer surface.
 * Note: We intentionally do NOT call eglTerminate(EGL_DEFAULT_DISPLAY) on teardown because
 * EGL_DEFAULT_DISPLAY is shared across the entire Android process (including HardwareRenderer,
 * MilkdropSurfaceView, and concurrent BlurImageView workers), and eglInitialize() always returns
 * EGL_TRUE even when the display was already initialized by another thread.
 */
struct EglGuard {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;

    EGLDisplay prevDisplay = EGL_NO_DISPLAY;
    EGLContext prevContext = EGL_NO_CONTEXT;
    EGLSurface prevDrawSurf = EGL_NO_SURFACE;
    EGLSurface prevReadSurf = EGL_NO_SURFACE;

    ~EglGuard() {
        if (display == EGL_NO_DISPLAY) return;

        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);

        if (prevContext != EGL_NO_CONTEXT && prevDisplay != EGL_NO_DISPLAY) {
            eglMakeCurrent(prevDisplay, prevDrawSurf, prevReadSurf, prevContext);
        }
    }
};

static GLuint compileShader(GLenum type, const char *src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);

    GLint status = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader compile error: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint createProgram(const char *vertSrc, const char *fragSrc) {
    GLuint vert = compileShader(GL_VERTEX_SHADER, vertSrc);
    if (!vert) return 0;
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, fragSrc);
    if (!frag) {
        glDeleteShader(vert);
        return 0;
    }

    GLuint prog = glCreateProgram();
    glAttachShader(prog, vert);
    glAttachShader(prog, frag);
    glLinkProgram(prog);
    glDeleteShader(vert);
    glDeleteShader(frag);

    GLint status = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetProgramInfoLog(prog, sizeof(log), nullptr, log);
        LOGE("Program link error: %s", log);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

static void computeGaussianWeights(float radius,
                                   float weights[WEIGHT_ARRAY_SIZE],
                                   int *outKernelRadius) {
    float sigma = radius / 3.0f;
    if (sigma < 0.001f) sigma = 0.001f;

    int kr = static_cast<int>(std::ceil(radius));
    if (kr > MAX_HALF_KERNEL) kr = MAX_HALF_KERNEL;
    *outKernelRadius = kr;

    float sum = 0.0f;
    for (int i = 0; i <= kr; i++) {
        float x = static_cast<float>(i);
        weights[i] = std::exp(-(x * x) / (2.0f * sigma * sigma));
        sum += (i == 0) ? weights[i] : 2.0f * weights[i];
    }
    for (int i = 0; i <= kr; i++) weights[i] /= sum;
}

static bool makeTexFbo(int w, int h, GLuint *tex, GLuint *fbo) {
    glGenTextures(1, tex);
    glBindTexture(GL_TEXTURE_2D, *tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    glGenFramebuffers(1, fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, *fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, *tex, 0);

    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        LOGE("Framebuffer incomplete for size %d x %d", w, h);
        return false;
    }
    return true;
}

uint8_t *gaussianBlurGpu(const uint8_t *pixels, int width, int height, float radius) {
    if (!pixels || width <= 0 || height <= 0) return nullptr;
    if (radius < 1.0f) radius = 1.0f;

    EglGuard egl;
    GlResources gl;

    egl.prevDisplay = eglGetCurrentDisplay();
    egl.prevContext = eglGetCurrentContext();
    egl.prevDrawSurf = eglGetCurrentSurface(EGL_DRAW);
    egl.prevReadSurf = eglGetCurrentSurface(EGL_READ);

    egl.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl.display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return nullptr;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(egl.display, &major, &minor)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        return nullptr;
    }

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

    EGLint configAttribs[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
    };

    EGLConfig config = nullptr;
    EGLint numConfig = 0;
    if (!eglChooseConfig(egl.display, configAttribs, &config, 1, &numConfig)
        || numConfig < 1) {
        LOGE("No suitable EGL config for OpenGL ES 3");
        return nullptr;
    }

    EGLint pbufAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    egl.surface = eglCreatePbufferSurface(egl.display, config, pbufAttribs);
    if (egl.surface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
        return nullptr;
    }

    EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    egl.context = eglCreateContext(egl.display, config,
                                   EGL_NO_CONTEXT, ctxAttribs);
    if (egl.context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        return nullptr;
    }

    if (!eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return nullptr;
    }

    gl.hblurProg = createProgram(VERT_SRC, HBLUR_FRAG_SRC);
    gl.vblurProg = createProgram(VERT_SRC, VBLUR_FRAG_SRC);
    gl.copyProg = createProgram(VERT_SRC, COPY_FRAG_SRC);
    if (!gl.hblurProg || !gl.vblurProg || !gl.copyProg) {
        LOGE("Shader compilation failed");
        return nullptr;
    }

    glGenVertexArrays(1, &gl.vao);
    glGenBuffers(1, &gl.vbo);
    glBindVertexArray(gl.vao);
    glBindBuffer(GL_ARRAY_BUFFER, gl.vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(QUAD_VERTS), QUAD_VERTS, GL_STATIC_DRAW);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(float), reinterpret_cast<void *>(0));
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(float), reinterpret_cast<void *>(2 * sizeof(float)));
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glGenTextures(1, &gl.srcTex);
    glBindTexture(GL_TEXTURE_2D, gl.srcTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    float effectiveRadius = radius;
    int workW = width;
    int workH = height;

    if (radius > static_cast<float>(MAX_HALF_KERNEL)) {
        float scale = static_cast<float>(MAX_HALF_KERNEL) / radius;
        workW = std::max(1, static_cast<int>(static_cast<float>(width) * scale));
        workH = std::max(1, static_cast<int>(static_cast<float>(height) * scale));
        effectiveRadius = static_cast<float>(MAX_HALF_KERNEL);
    }

    GLuint blurInputTex = gl.srcTex;

    if (workW != width || workH != height) {
        if (!makeTexFbo(workW, workH, &gl.downTex, &gl.downFbo)) return nullptr;
        glViewport(0, 0, workW, workH);
        glUseProgram(gl.copyProg);
        glUniform1i(glGetUniformLocation(gl.copyProg, "uTexture"), 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gl.srcTex);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        blurInputTex = gl.downTex;
    }

    float weights[WEIGHT_ARRAY_SIZE] = {};
    int kernelRadius = 0;
    computeGaussianWeights(effectiveRadius, weights, &kernelRadius);

    if (!makeTexFbo(workW, workH, &gl.tmpTex, &gl.tmpFbo)) return nullptr;
    glBindFramebuffer(GL_FRAMEBUFFER, gl.tmpFbo);
    glViewport(0, 0, workW, workH);
    glUseProgram(gl.hblurProg);
    glUniform1i(glGetUniformLocation(gl.hblurProg, "uTexture"), 0);
    glUniform1fv(glGetUniformLocation(gl.hblurProg, "uWeights"), WEIGHT_ARRAY_SIZE, weights);
    glUniform1i(glGetUniformLocation(gl.hblurProg, "uKernelRadius"), kernelRadius);
    glUniform1f(glGetUniformLocation(gl.hblurProg, "uTexelWidth"),
                1.0f / static_cast<float>(workW));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, blurInputTex);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    if (!makeTexFbo(workW, workH, &gl.blurTex, &gl.blurFbo)) return nullptr;
    glBindFramebuffer(GL_FRAMEBUFFER, gl.blurFbo);
    glUseProgram(gl.vblurProg);
    glUniform1i(glGetUniformLocation(gl.vblurProg, "uTexture"), 0);
    glUniform1fv(glGetUniformLocation(gl.vblurProg, "uWeights"), WEIGHT_ARRAY_SIZE, weights);
    glUniform1i(glGetUniformLocation(gl.vblurProg, "uKernelRadius"), kernelRadius);
    glUniform1f(glGetUniformLocation(gl.vblurProg, "uTexelHeight"),
                1.0f / static_cast<float>(workH));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gl.tmpTex);
    glDrawArrays(GL_TRIANGLES, 0, 6);

    GLuint readFbo = gl.blurFbo;
    int readW = workW;
    int readH = workH;

    if (workW != width || workH != height) {
        if (!makeTexFbo(width, height, &gl.upTex, &gl.upFbo)) return nullptr;
        glBindFramebuffer(GL_FRAMEBUFFER, gl.upFbo);
        glViewport(0, 0, width, height);
        glUseProgram(gl.copyProg);
        glUniform1i(glGetUniformLocation(gl.copyProg, "uTexture"), 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gl.blurTex);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        readFbo = gl.upFbo;
        readW = width;
        readH = height;
    }

    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    auto *result = new uint8_t[static_cast<size_t>(readW) * readH * 4];
    glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
    glReadPixels(0, 0, readW, readH, GL_RGBA, GL_UNSIGNED_BYTE, result);

    return result;
}
