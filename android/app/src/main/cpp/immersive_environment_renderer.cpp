// SPDX-License-Identifier: AGPL-3.0-only

#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <EGL/egl.h>
#include <openxr/openxr_platform.h>
#include "immersive_environment_renderer.h"

#include <android/asset_manager.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#define ENV_LOG_TAG "HorizonStream_Environment"
#define ENV_LOGI(...) __android_log_print(ANDROID_LOG_INFO, ENV_LOG_TAG, __VA_ARGS__)
#define ENV_LOGW(...) __android_log_print(ANDROID_LOG_WARN, ENV_LOG_TAG, __VA_ARGS__)
#define ENV_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ENV_LOG_TAG, __VA_ARGS__)

namespace {

constexpr uint32_t kMeshHeaderBytes = 20;
constexpr uint32_t kMeshPartBytes = 12;
constexpr uint32_t kMeshVertexFloats = 11;
constexpr uint32_t kAtlasHeaderBytes = 24;
constexpr uint32_t kEnvironmentEyeSize = 1024;
// Room rendering shares the decoder's GL context. Restore state on every
// return path, including asset and swapchain failures.
struct ScopedGraphicsState {
    GLint framebuffer, vertexArray, arrayBuffer, program, activeTexture;
    GLint texture, renderbuffer, viewport[4];
    GLboolean depthTest, depthMask, blend, cull, scissor;
    ScopedGraphicsState() {
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &framebuffer);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vertexArray);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &activeTexture);
        glGetIntegerv(GL_RENDERBUFFER_BINDING, &renderbuffer);
        glGetIntegerv(GL_VIEWPORT, viewport);
        depthTest = glIsEnabled(GL_DEPTH_TEST);
        blend = glIsEnabled(GL_BLEND);
        cull = glIsEnabled(GL_CULL_FACE);
        scissor = glIsEnabled(GL_SCISSOR_TEST);
        glGetBooleanv(GL_DEPTH_WRITEMASK, &depthMask);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture);
    }
    ~ScopedGraphicsState() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glBindVertexArray(vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);
        glActiveTexture(activeTexture);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        glDepthMask(depthMask);
    }
};
struct Part {
    uint32_t firstIndex;
    uint32_t indexCount;
    int32_t atlas;
};

struct EnvironmentConfig {
    const char* name;
    const char* meshAsset;
    const char* atlasAsset;
    float anchorY;
    float anchorZ;
    float screenY;
    float screenZ;
    float screenWidth;
    float screenHeight;
    float screenFraction;
    float farPlane;
};

// Coordinates and screen anchors are measured from the upstream baked room
// assets. The room geometry is translated so its seated viewer point is the
// origin of the OpenXR LOCAL space.
const EnvironmentConfig kEnvironments[] = {
    { "Home Theater", "rooms/home_theater.room", "rooms/home_theater_lo_0.atlas",
      1.50f, 1.02f, 0.05f, -4.152f, 3.60f, 2.025f, 0.80f, 24.0f },
    { "Grand Cinema", "rooms/grand_cinema.room", "rooms/grand_cinema_lo_0.atlas",
      5.20f, 6.00f, 2.20f, -21.42f, 22.0f, 12.375f, 1.00f, 80.0f },
    { "Synthwave", "rooms/synthwave.room", "rooms/synthwave_lo_0.atlas",
      1.55f, -0.10f, 2.95f, -13.90f, 14.0f, 7.875f, 1.00f, 2000.0f }
};

uint32_t readU32(const uint8_t* data) {
    uint32_t value = 0;
    std::memcpy(&value, data, sizeof(value));
    return value;
}

int32_t readI32(const uint8_t* data) {
    int32_t value = 0;
    std::memcpy(&value, data, sizeof(value));
    return value;
}

bool readAsset(AAssetManager* manager, const char* path, std::vector<uint8_t>* out) {
    if (!manager || !path || !out)
        return false;
    AAsset* asset = AAssetManager_open(manager, path, AASSET_MODE_BUFFER);
    if (!asset) {
        ENV_LOGE("Unable to open room asset %s.", path);
        return false;
    }
    const off_t length = AAsset_getLength(asset);
    if (length <= 0 || static_cast<uint64_t>(length) > 64ULL * 1024ULL * 1024ULL) {
        AAsset_close(asset);
        ENV_LOGE("Room asset %s has an invalid size (%lld).", path,
                 static_cast<long long>(length));
        return false;
    }
    out->resize(static_cast<size_t>(length));
    const int64_t read = AAsset_read(asset, out->data(), out->size());
    AAsset_close(asset);
    if (read != length) {
        out->clear();
        ENV_LOGE("Room asset %s was read incompletely.", path);
        return false;
    }
    return true;
}

bool parseMesh(const std::vector<uint8_t>& bytes,
               std::vector<float>* vertices,
               std::vector<uint32_t>* indices,
               std::vector<Part>* parts) {
    if (bytes.size() < kMeshHeaderBytes || std::memcmp(bytes.data(), "MXR3", 4) != 0)
        return false;
    const uint32_t vertexCount = readU32(bytes.data() + 4);
    const uint32_t indexCount = readU32(bytes.data() + 8);
    const uint32_t partCount = readU32(bytes.data() + 12);
    const uint32_t atlasCount = readU32(bytes.data() + 16);
    if (vertexCount == 0 || vertexCount > (1U << 20) || indexCount == 0 ||
        indexCount % 3 != 0 || partCount == 0 || partCount > 16 || atlasCount > 4)
        return false;

    const uint64_t vertexOffset = kMeshHeaderBytes +
        static_cast<uint64_t>(partCount) * kMeshPartBytes + 4;
    const uint64_t vertexBytes = static_cast<uint64_t>(vertexCount) *
        kMeshVertexFloats * sizeof(float);
    const uint64_t indexOffset = vertexOffset + vertexBytes;
    const uint64_t expectedSize = indexOffset + static_cast<uint64_t>(indexCount) * sizeof(uint32_t);
    if (expectedSize != bytes.size() || vertexOffset > bytes.size() ||
        indexOffset > bytes.size())
        return false;

    const uint64_t partsOffset = kMeshHeaderBytes;
    uint32_t expectedFirst = 0;
    for (uint32_t i = 0; i < partCount; ++i) {
        const uint8_t* record = bytes.data() + partsOffset + i * kMeshPartBytes;
        Part part{ readU32(record), readU32(record + 4), readI32(record + 8) };
        if (part.firstIndex != expectedFirst || part.indexCount == 0 ||
            part.indexCount % 3 != 0 || part.indexCount > indexCount - expectedFirst ||
            part.atlas < -1 || part.atlas >= static_cast<int32_t>(atlasCount))
            return false;
        expectedFirst += part.indexCount;
        parts->push_back(part);
    }
    if (expectedFirst != indexCount || readU32(bytes.data() + vertexOffset - 4) != 0)
        return false;

    const float* source = reinterpret_cast<const float*>(bytes.data() + vertexOffset);
    vertices->reserve(static_cast<size_t>(vertexCount) * 8);
    for (uint32_t i = 0; i < vertexCount; ++i) {
        const float* v = source + static_cast<size_t>(i) * kMeshVertexFloats;
        // Position, UV, vertex colour. Normals are not needed because the
        // baked atlases already contain the room lighting.
        vertices->insert(vertices->end(), { v[0], v[1], v[2], v[6], v[7],
                                             v[8], v[9], v[10] });
    }

    indices->resize(indexCount);
    std::memcpy(indices->data(), bytes.data() + indexOffset,
                static_cast<size_t>(indexCount) * sizeof(uint32_t));
    for (uint32_t index : *indices) {
        if (index >= vertexCount)
            return false;
    }
    return true;
}

bool uploadAtlas(const std::vector<uint8_t>& bytes, GLuint* texture) {
    if (bytes.size() < kAtlasHeaderBytes || std::memcmp(bytes.data(), "MXA1", 4) != 0)
        return false;
    const uint32_t width = readU32(bytes.data() + 4);
    const uint32_t height = readU32(bytes.data() + 8);
    const uint32_t blockWidth = readU32(bytes.data() + 12);
    const uint32_t blockHeight = readU32(bytes.data() + 16);
    const uint32_t levels = readU32(bytes.data() + 20);
    if (width == 0 || height == 0 || width > 8192 || height > 8192 ||
        blockWidth != 6 || blockHeight != 6 || levels == 0 || levels > 14)
        return false;

    const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (!extensions || !std::strstr(extensions, "GL_KHR_texture_compression_astc_ldr")) {
        ENV_LOGE("This graphics driver does not support ASTC room textures.");
        return false;
    }

    struct Level { uint32_t width; uint32_t height; uint32_t size; size_t offset; };
    std::vector<Level> mipLevels;
    size_t offset = kAtlasHeaderBytes;
    for (uint32_t i = 0; i < levels; ++i) {
        if (bytes.size() - offset < 4)
            return false;
        const uint32_t levelSize = readU32(bytes.data() + offset);
        offset += 4;
        const uint32_t levelWidth = std::max(1U, width >> i);
        const uint32_t levelHeight = std::max(1U, height >> i);
        const uint64_t expected =
            static_cast<uint64_t>((levelWidth + 5) / 6) *
            static_cast<uint64_t>((levelHeight + 5) / 6) * 16;
        if (expected != levelSize || bytes.size() - offset < levelSize)
            return false;
        mipLevels.push_back({ levelWidth, levelHeight, levelSize, offset });
        offset += levelSize;
    }
    if (offset != bytes.size())
        return false;

    glGenTextures(1, texture);
    glBindTexture(GL_TEXTURE_2D, *texture);
    for (size_t i = 0; i < mipLevels.size(); ++i) {
        const Level& level = mipLevels[i];
        glCompressedTexImage2D(GL_TEXTURE_2D, static_cast<GLint>(i),
            GL_COMPRESSED_RGBA_ASTC_6x6_KHR, static_cast<GLsizei>(level.width),
            static_cast<GLsizei>(level.height), 0, static_cast<GLsizei>(level.size),
            bytes.data() + level.offset);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_BASE_LEVEL, 0);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL,
                    static_cast<GLint>(mipLevels.size() - 1));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    const GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    if (error != GL_NO_ERROR) {
        ENV_LOGE("ASTC room texture upload failed with GLES error 0x%x.", error);
        glDeleteTextures(1, texture);
        *texture = 0;
        return false;
    }
    return true;
}

GLuint compileShader(GLenum type, const char* source) {
    const GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled == GL_TRUE)
        return shader;
    char log[768] = {};
    glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
    ENV_LOGE("Environment shader compilation failed: %s", log);
    glDeleteShader(shader);
    return 0;
}

void multiplyMatrix(const float* a, const float* b, float* out) {
    for (int column = 0; column < 4; ++column) {
        for (int row = 0; row < 4; ++row) {
            out[column * 4 + row] = 0.0f;
            for (int k = 0; k < 4; ++k)
                out[column * 4 + row] += a[k * 4 + row] * b[column * 4 + k];
        }
    }
}

void projectionFromFov(const XrFovf& fov, float nearZ, float farZ, float* out) {
    const float left = std::tan(fov.angleLeft);
    const float right = std::tan(fov.angleRight);
    const float down = std::tan(fov.angleDown);
    const float up = std::tan(fov.angleUp);
    std::memset(out, 0, sizeof(float) * 16);
    out[0] = 2.0f / (right - left);
    out[5] = 2.0f / (up - down);
    out[8] = (right + left) / (right - left);
    out[9] = (up + down) / (up - down);
    out[10] = -(farZ + nearZ) / (farZ - nearZ);
    out[11] = -1.0f;
    out[14] = -(2.0f * farZ * nearZ) / (farZ - nearZ);
}

void viewFromPose(const XrPosef& pose, float* out) {
    const float x = pose.orientation.x;
    const float y = pose.orientation.y;
    const float z = pose.orientation.z;
    const float w = pose.orientation.w;
    const float xx = x * x;
    const float yy = y * y;
    const float zz = z * z;
    const float xy = x * y;
    const float xz = x * z;
    const float yz = y * z;
    const float wx = w * x;
    const float wy = w * y;
    const float wz = w * z;
    const float r00 = 1.0f - 2.0f * (yy + zz);
    const float r01 = 2.0f * (xy - wz);
    const float r02 = 2.0f * (xz + wy);
    const float r10 = 2.0f * (xy + wz);
    const float r11 = 1.0f - 2.0f * (xx + zz);
    const float r12 = 2.0f * (yz - wx);
    const float r20 = 2.0f * (xz - wy);
    const float r21 = 2.0f * (yz + wx);
    const float r22 = 1.0f - 2.0f * (xx + yy);
    std::memset(out, 0, sizeof(float) * 16);
    // Inverse rotation is the transpose of the pose's rotation matrix.
    out[0] = r00; out[4] = r10; out[8] = r20;
    out[1] = r01; out[5] = r11; out[9] = r21;
    out[2] = r02; out[6] = r12; out[10] = r22;
    out[15] = 1.0f;
    const float px = pose.position.x;
    const float py = pose.position.y;
    const float pz = pose.position.z;
    out[12] = -(r00 * px + r10 * py + r20 * pz);
    out[13] = -(r01 * px + r11 * py + r21 * pz);
    out[14] = -(r02 * px + r12 * py + r22 * pz);
}

} // namespace

struct ImmersiveEnvironmentRenderer::Impl {
    AAssetManager* assets = nullptr;
    XrInstance instance = XR_NULL_HANDLE;
    XrSession session = XR_NULL_HANDLE;
    XrSpace appSpace = XR_NULL_HANDLE;
    int64_t colorFormat = GL_RGBA8;
    std::atomic<int> requestedEnvironment{0};
    int activeEnvironment = -1;
    int failedEnvironment = -1;
    PFN_xrCreateSwapchain createSwapchain = nullptr;
    PFN_xrEnumerateSwapchainImages enumerateSwapchainImages = nullptr;
    PFN_xrAcquireSwapchainImage acquireSwapchainImage = nullptr;
    PFN_xrWaitSwapchainImage waitSwapchainImage = nullptr;
    PFN_xrReleaseSwapchainImage releaseSwapchainImage = nullptr;
    PFN_xrDestroySwapchain destroySwapchain = nullptr;
    PFN_xrLocateViews locateViews = nullptr;
    PFN_xrCreatePassthroughFB createPassthrough = nullptr;
    PFN_xrDestroyPassthroughFB destroyPassthrough = nullptr;
    PFN_xrPassthroughStartFB startPassthrough = nullptr;
    PFN_xrPassthroughPauseFB pausePassthrough = nullptr;
    PFN_xrCreatePassthroughLayerFB createPassthroughLayer = nullptr;
    PFN_xrDestroyPassthroughLayerFB destroyPassthroughLayer = nullptr;
    PFN_xrPassthroughLayerPauseFB pausePassthroughLayer = nullptr;
    PFN_xrPassthroughLayerResumeFB resumePassthroughLayer = nullptr;
    XrSwapchain eyeSwapchains[2] = { XR_NULL_HANDLE, XR_NULL_HANDLE };
    std::vector<XrSwapchainImageOpenGLESKHR> eyeImages[2];
    XrCompositionLayerProjection projectionLayer{ XR_TYPE_COMPOSITION_LAYER_PROJECTION };
    XrCompositionLayerProjectionView projectionViews[2] = {
        { XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW },
        { XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW }
    };
    XrPassthroughFB passthrough = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughHandle = XR_NULL_HANDLE;
    XrCompositionLayerPassthroughFB passthroughCompositionLayer{
        XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB };
    std::vector<float> vertices;
    std::vector<uint32_t> indices;
    std::vector<Part> parts;
    GLuint vertexBuffer = 0;
    GLuint vertexArray = 0;
    GLuint indexBuffer = 0;
    GLuint atlasTexture = 0;
    GLuint whiteTexture = 0;
    GLuint framebuffer = 0;
    GLuint depthBuffer = 0;
    GLuint program = 0;
    GLint viewProjectionLocation = -1;
    GLint colorLocation = -1;
    GLint uvLocation = -1;
    GLint roomTextureLocation = -1;
    GLint texturedLocation = -1;
    bool functionsReady = false;
    bool passthroughFunctionsReady = false;
    bool passthroughActive = false;
    bool passthroughFailed = false;
    bool layerReady = false;

    bool loadOpenXrFunctions(PFN_xrGetInstanceProcAddr getProc) {
        if (!getProc)
            return false;
#define LOAD_XR_FUNCTION(field, name) \
        if (XR_FAILED(getProc(instance, "xr" name, \
                reinterpret_cast<PFN_xrVoidFunction*>(&field)))) { \
            ENV_LOGE("OpenXR function xr%s is unavailable.", name); \
            return false; \
        }
        LOAD_XR_FUNCTION(createSwapchain, "CreateSwapchain")
        LOAD_XR_FUNCTION(enumerateSwapchainImages, "EnumerateSwapchainImages")
        LOAD_XR_FUNCTION(acquireSwapchainImage, "AcquireSwapchainImage")
        LOAD_XR_FUNCTION(waitSwapchainImage, "WaitSwapchainImage")
        LOAD_XR_FUNCTION(releaseSwapchainImage, "ReleaseSwapchainImage")
        LOAD_XR_FUNCTION(destroySwapchain, "DestroySwapchain")
        LOAD_XR_FUNCTION(locateViews, "LocateViews")
#undef LOAD_XR_FUNCTION
        return true;
    }

    bool loadPassthroughFunctions(PFN_xrGetInstanceProcAddr getProc) {
        if (!getProc)
            return false;
#define LOAD_PASSTHROUGH_FUNCTION(field, name) \
        if (XR_FAILED(getProc(instance, "xr" name, \
                reinterpret_cast<PFN_xrVoidFunction*>(&field)))) { \
            ENV_LOGW("Passthrough function xr%s is unavailable.", name); \
            return false; \
        }
        LOAD_PASSTHROUGH_FUNCTION(createPassthrough, "CreatePassthroughFB")
        LOAD_PASSTHROUGH_FUNCTION(destroyPassthrough, "DestroyPassthroughFB")
        LOAD_PASSTHROUGH_FUNCTION(startPassthrough, "PassthroughStartFB")
        LOAD_PASSTHROUGH_FUNCTION(pausePassthrough, "PassthroughPauseFB")
        LOAD_PASSTHROUGH_FUNCTION(createPassthroughLayer, "CreatePassthroughLayerFB")
        LOAD_PASSTHROUGH_FUNCTION(destroyPassthroughLayer, "DestroyPassthroughLayerFB")
        LOAD_PASSTHROUGH_FUNCTION(pausePassthroughLayer, "PassthroughLayerPauseFB")
        LOAD_PASSTHROUGH_FUNCTION(resumePassthroughLayer, "PassthroughLayerResumeFB")
#undef LOAD_PASSTHROUGH_FUNCTION
        return true;
    }

    void stopPassthrough() {
        if (passthroughHandle != XR_NULL_HANDLE) {
            if (passthroughActive && pausePassthroughLayer)
                pausePassthroughLayer(passthroughHandle);
            if (destroyPassthroughLayer)
                destroyPassthroughLayer(passthroughHandle);
            passthroughHandle = XR_NULL_HANDLE;
        }
        if (passthrough != XR_NULL_HANDLE) {
            if (passthroughActive && pausePassthrough)
                pausePassthrough(passthrough);
            if (destroyPassthrough)
                destroyPassthrough(passthrough);
            passthrough = XR_NULL_HANDLE;
        }
        passthroughActive = false;
    }

    bool ensurePassthrough() {
        if (!passthroughFunctionsReady || passthroughFailed)
            return false;
        if (passthroughHandle != XR_NULL_HANDLE) {
            if (!passthroughActive) {
                if (XR_FAILED(startPassthrough(passthrough))) {
                    ENV_LOGW("Unable to resume the passthrough environment.");
                    return false;
                }
                if (XR_FAILED(resumePassthroughLayer(passthroughHandle))) {
                    pausePassthrough(passthrough);
                    ENV_LOGW("Unable to resume the passthrough composition layer.");
                    return false;
                }
                passthroughActive = true;
            }
            return true;
        }

        XrPassthroughCreateInfoFB passthroughInfo{
            XR_TYPE_PASSTHROUGH_CREATE_INFO_FB };
        passthroughInfo.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
        if (XR_FAILED(createPassthrough(session, &passthroughInfo, &passthrough))) {
            ENV_LOGW("Unable to create the passthrough environment.");
            passthrough = XR_NULL_HANDLE;
            passthroughFailed = true;
            return false;
        }
        passthroughActive = true;
        XrPassthroughLayerCreateInfoFB layerInfo{
            XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB };
        layerInfo.passthrough = passthrough;
        layerInfo.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
        layerInfo.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
        if (XR_FAILED(createPassthroughLayer(session, &layerInfo, &passthroughHandle))) {
            ENV_LOGW("Unable to create the passthrough composition layer.");
            stopPassthrough();
            passthroughFailed = true;
            return false;
        }
        passthroughCompositionLayer.type = XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB;
        passthroughCompositionLayer.next = nullptr;
        passthroughCompositionLayer.flags =
            XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        passthroughCompositionLayer.space = XR_NULL_HANDLE;
        passthroughCompositionLayer.layerHandle = passthroughHandle;
        passthroughActive = true;
        ENV_LOGI("Meta passthrough environment started.");
        return true;
    }

    void pausePassthroughIfActive() {
        if (!passthroughActive)
            return;
        if (passthroughHandle != XR_NULL_HANDLE && pausePassthroughLayer)
            pausePassthroughLayer(passthroughHandle);
        if (passthrough != XR_NULL_HANDLE && pausePassthrough)
            pausePassthrough(passthrough);
        passthroughActive = false;
    }

    bool createEyeSwapchain(int eye) {
        XrSwapchainCreateInfo info{ XR_TYPE_SWAPCHAIN_CREATE_INFO };
        info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                          XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        info.format = colorFormat;
        info.sampleCount = 1;
        info.width = kEnvironmentEyeSize;
        info.height = kEnvironmentEyeSize;
        info.faceCount = 1;
        info.arraySize = 1;
        info.mipCount = 1;
        if (XR_FAILED(createSwapchain(session, &info, &eyeSwapchains[eye])))
            return false;
        uint32_t count = 0;
        if (XR_FAILED(enumerateSwapchainImages(eyeSwapchains[eye], 0, &count, nullptr)) ||
            count == 0)
            return false;
        eyeImages[eye].resize(count);
        for (auto& image : eyeImages[eye])
            image = { XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR };
        if (XR_FAILED(enumerateSwapchainImages(eyeSwapchains[eye], count, &count,
                reinterpret_cast<XrSwapchainImageBaseHeader*>(eyeImages[eye].data()))) ||
            count == 0) {
            eyeImages[eye].clear();
            return false;
        }
        return true;
    }

    bool ensureSwapchains() {
        if (eyeSwapchains[0] != XR_NULL_HANDLE && eyeSwapchains[1] != XR_NULL_HANDLE)
            return true;
        if (!createEyeSwapchain(0) || !createEyeSwapchain(1)) {
            ENV_LOGE("Unable to create the environment projection swapchains.");
            for (int eye = 0; eye < 2; ++eye) {
                if (eyeSwapchains[eye] != XR_NULL_HANDLE && destroySwapchain) {
                    destroySwapchain(eyeSwapchains[eye]);
                    eyeSwapchains[eye] = XR_NULL_HANDLE;
                }
                eyeImages[eye].clear();
            }
            return false;
        }
        projectionLayer.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
        projectionLayer.space = appSpace;
        projectionLayer.viewCount = 2;
        projectionLayer.views = projectionViews;
        for (int eye = 0; eye < 2; ++eye) {
            projectionViews[eye].type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
            projectionViews[eye].subImage.swapchain = eyeSwapchains[eye];
            projectionViews[eye].subImage.imageRect.offset = { 0, 0 };
            projectionViews[eye].subImage.imageRect.extent = {
                static_cast<int32_t>(kEnvironmentEyeSize),
                static_cast<int32_t>(kEnvironmentEyeSize)
            };
            projectionViews[eye].subImage.imageArrayIndex = 0;
        }
        return true;
    }

    void releaseRoom() {
        if (vertexArray) glDeleteVertexArrays(1, &vertexArray);
        if (vertexBuffer) glDeleteBuffers(1, &vertexBuffer);
        if (indexBuffer) glDeleteBuffers(1, &indexBuffer);
        if (atlasTexture) glDeleteTextures(1, &atlasTexture);
        vertexBuffer = indexBuffer = atlasTexture = 0;
        vertexArray = 0;
        vertices.clear();
        indices.clear();
        parts.clear();
        activeEnvironment = -1;
        layerReady = false;
    }

    bool loadRoom(int environment) {
        releaseRoom();
        if (environment < 1 || environment > 3)
            return false;
        const EnvironmentConfig& config = kEnvironments[environment - 1];
        std::vector<uint8_t> meshBytes;
        std::vector<uint8_t> atlasBytes;
        if (!readAsset(assets, config.meshAsset, &meshBytes) ||
            !parseMesh(meshBytes, &vertices, &indices, &parts) ||
            !readAsset(assets, config.atlasAsset, &atlasBytes) ||
            !uploadAtlas(atlasBytes, &atlasTexture)) {
            ENV_LOGE("The %s environment could not be loaded; keeping the black background.",
                     config.name);
            releaseRoom();
            failedEnvironment = environment;
            return false;
        }

        const float anchorX = 0.0f;
        const size_t vertexCount = vertices.size() / 8;
        for (size_t i = 0; i < vertexCount; ++i) {
            float* vertex = vertices.data() + i * 8;
            vertex[0] -= anchorX;
            vertex[1] -= config.anchorY;
            vertex[2] -= config.anchorZ;
        }
        glGenVertexArrays(1, &vertexArray);
        glBindVertexArray(vertexArray);
        glGenBuffers(1, &vertexBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(vertices.size() * sizeof(float)),
            vertices.data(), GL_STATIC_DRAW);
        glGenBuffers(1, &indexBuffer);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER,
            static_cast<GLsizeiptr>(indices.size() * sizeof(uint32_t)),
            indices.data(), GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

        if (!program) {
            static const char* vertexShaderSource =
                "#version 300 es\n"
                "precision highp float;\n"
                "in vec3 aPosition; in vec2 aUv; in vec3 aColor;\n"
                "uniform mat4 uViewProjection;\n"
                "out vec2 vUv; out vec3 vColor;\n"
                "void main(){ vUv=aUv; vColor=aColor; "
                "gl_Position=uViewProjection*vec4(aPosition,1.0); }\n";
            static const char* fragmentShaderSource =
                "#version 300 es\n"
                "precision mediump float;\n"
                "in vec2 vUv; in vec3 vColor;\n"
                "uniform sampler2D uRoomTexture; uniform float uTextured;\n"
                "out vec4 fragColor;\n"
                "void main(){ vec3 base=mix(vColor,texture(uRoomTexture,vUv).rgb,uTextured);"
                "fragColor=vec4(clamp(base,0.0,1.0),1.0); }\n";
            const GLuint vs = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
            const GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource);
            if (!vs || !fs) {
                if (vs) glDeleteShader(vs);
                if (fs) glDeleteShader(fs);
                releaseRoom();
                failedEnvironment = environment;
                return false;
            }
            program = glCreateProgram();
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glBindAttribLocation(program, 0, "aPosition");
            glBindAttribLocation(program, 1, "aUv");
            glBindAttribLocation(program, 2, "aColor");
            glLinkProgram(program);
            glDeleteShader(vs);
            glDeleteShader(fs);
            GLint linked = GL_FALSE;
            glGetProgramiv(program, GL_LINK_STATUS, &linked);
            if (linked != GL_TRUE) {
                char log[768] = {};
                glGetProgramInfoLog(program, sizeof(log), nullptr, log);
                ENV_LOGE("Environment shader link failed: %s", log);
                glDeleteProgram(program);
                program = 0;
                releaseRoom();
                failedEnvironment = environment;
                return false;
            }
            viewProjectionLocation = glGetUniformLocation(program, "uViewProjection");
            roomTextureLocation = glGetUniformLocation(program, "uRoomTexture");
            texturedLocation = glGetUniformLocation(program, "uTextured");
            colorLocation = glGetAttribLocation(program, "aColor");
            uvLocation = glGetAttribLocation(program, "aUv");
            glUseProgram(program);
            glUniform1i(roomTextureLocation, 0);
        }

        if (!whiteTexture) {
            const uint8_t white[] = { 255, 255, 255, 255 };
            glGenTextures(1, &whiteTexture);
            glBindTexture(GL_TEXTURE_2D, whiteTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, white);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        }
        if (!framebuffer) {
            glGenFramebuffers(1, &framebuffer);
            glGenRenderbuffers(1, &depthBuffer);
            glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24,
                                  kEnvironmentEyeSize, kEnvironmentEyeSize);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                                      GL_RENDERBUFFER, depthBuffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
        activeEnvironment = environment;
        failedEnvironment = -1;
        ENV_LOGI("Loaded %s room environment (%zu vertices, %zu triangles).",
                 config.name, vertexCount, indices.size() / 3);
        return true;
    }

    bool renderEye(int eye, const XrView& view) {
        uint32_t imageIndex = 0;
        XrSwapchainImageAcquireInfo acquire{ XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO };
        if (XR_FAILED(acquireSwapchainImage(eyeSwapchains[eye], &acquire, &imageIndex)))
            return false;
        XrSwapchainImageWaitInfo wait{ XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO };
        wait.timeout = XR_INFINITE_DURATION;
        if (XR_FAILED(waitSwapchainImage(eyeSwapchains[eye], &wait))) {
            XrSwapchainImageReleaseInfo release{ XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO };
            releaseSwapchainImage(eyeSwapchains[eye], &release);
            return false;
        }

        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               eyeImages[eye][imageIndex].image, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            XrSwapchainImageReleaseInfo release{ XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO };
            releaseSwapchainImage(eyeSwapchains[eye], &release);
            ENV_LOGE("Environment projection framebuffer is incomplete.");
            return false;
        }

        glViewport(0, 0, kEnvironmentEyeSize, kEnvironmentEyeSize);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(GL_TRUE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glClearColor(0.008f, 0.009f, 0.014f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        float projection[16];
        float viewMatrix[16];
        float viewProjection[16];
        const EnvironmentConfig& config = kEnvironments[activeEnvironment - 1];
        projectionFromFov(view.fov, 0.05f, config.farPlane, projection);
        viewFromPose(view.pose, viewMatrix);
        multiplyMatrix(projection, viewMatrix, viewProjection);

        glUseProgram(program);
        glUniformMatrix4fv(viewProjectionLocation, 1, GL_FALSE, viewProjection);
        glUniform1i(roomTextureLocation, 0);
        glBindVertexArray(vertexArray);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        constexpr GLsizei stride = 8 * sizeof(float);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, stride, nullptr);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, stride,
                              reinterpret_cast<const void*>(3 * sizeof(float)));
        glVertexAttribPointer(2, 3, GL_FLOAT, GL_FALSE, stride,
                              reinterpret_cast<const void*>(5 * sizeof(float)));
        glEnableVertexAttribArray(0);
        glEnableVertexAttribArray(1);
        glEnableVertexAttribArray(2);
        glActiveTexture(GL_TEXTURE0);
        for (const Part& part : parts) {
            const bool textured = part.atlas >= 0 && atlasTexture != 0;
            glBindTexture(GL_TEXTURE_2D, textured ? atlasTexture : whiteTexture);
            glUniform1f(texturedLocation, textured ? 1.0f : 0.0f);
            glDrawElements(GL_TRIANGLES, static_cast<GLsizei>(part.indexCount),
                GL_UNSIGNED_INT,
                reinterpret_cast<const void*>(static_cast<uintptr_t>(part.firstIndex) *
                                               sizeof(uint32_t)));
        }
        const GLenum drawError = glGetError();
        if (drawError != GL_NO_ERROR)
            ENV_LOGE("Environment draw failed with GLES error 0x%x.", drawError);
        // Submit GL commands before handing the image back to the compositor.
        glFlush();
        XrSwapchainImageReleaseInfo release{ XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO };
        const bool released = XR_SUCCEEDED(
            releaseSwapchainImage(eyeSwapchains[eye], &release));
        return drawError == GL_NO_ERROR && released;
    }
};

ImmersiveEnvironmentRenderer::ImmersiveEnvironmentRenderer() : impl_(new Impl()) {}

ImmersiveEnvironmentRenderer::~ImmersiveEnvironmentRenderer() {
    if (impl_) {
        shutdown();
        delete impl_;
        impl_ = nullptr;
    }
}

bool ImmersiveEnvironmentRenderer::initialize(AAssetManager* assets,
                                               XrInstance instance,
                                               XrSession session,
                                               XrSpace appSpace,
                                               PFN_xrGetInstanceProcAddr getProc,
                                               int64_t colorFormat,
                                               bool passthroughEnabled) {
    if (!impl_)
        return false;
    impl_->assets = assets;
    impl_->instance = instance;
    impl_->session = session;
    impl_->appSpace = appSpace;
    impl_->colorFormat = colorFormat;
    impl_->activeEnvironment = -1;
    impl_->failedEnvironment = -1;
    impl_->functionsReady = impl_->loadOpenXrFunctions(getProc);
    impl_->passthroughFunctionsReady = passthroughEnabled &&
        impl_->loadPassthroughFunctions(getProc);
    impl_->passthroughFailed = false;
    return impl_->functionsReady;
}

void ImmersiveEnvironmentRenderer::setEnvironment(int environment) {
    if (!impl_)
        return;
    impl_->requestedEnvironment.store(std::max(0, std::min(4, environment)),
                                     std::memory_order_release);
}

void ImmersiveEnvironmentRenderer::pausePassthrough() {
    if (impl_)
        impl_->pausePassthroughIfActive();
}

bool ImmersiveEnvironmentRenderer::render(XrTime displayTime) {
    if (!impl_ || !impl_->functionsReady)
        return false;
    ScopedGraphicsState graphicsState;
    const int requested = impl_->requestedEnvironment.load(std::memory_order_acquire);
    impl_->layerReady = false;
    if (requested == 4) {
        if (impl_->activeEnvironment != -1)
            impl_->releaseRoom();
        return impl_->ensurePassthrough();
    }
    impl_->pausePassthroughIfActive();
    if (requested == 0) {
        if (impl_->activeEnvironment != -1)
            impl_->releaseRoom();
        return false;
    }
    if (requested != impl_->activeEnvironment) {
        if (requested == impl_->failedEnvironment)
            return false;
        if (!impl_->loadRoom(requested))
            return false;
    }
    if (!impl_->ensureSwapchains())
        return false;

    XrViewLocateInfo locateInfo{ XR_TYPE_VIEW_LOCATE_INFO };
    locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locateInfo.displayTime = displayTime;
    locateInfo.space = impl_->appSpace;
    XrViewState viewState{ XR_TYPE_VIEW_STATE };
    XrView views[2] = { { XR_TYPE_VIEW }, { XR_TYPE_VIEW } };
    XrViewStateFlags flags = 0;
    uint32_t viewCount = 0;
    const XrResult locateResult = impl_->locateViews(
        impl_->session, &locateInfo, &viewState, 2, &viewCount, views);
    if (XR_FAILED(locateResult) || viewCount != 2 ||
        (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) == 0 ||
        (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) == 0)
        return false;

    for (int eye = 0; eye < 2; ++eye) {
        if (!impl_->renderEye(eye, views[eye]))
            return false;
        impl_->projectionViews[eye].pose = views[eye].pose;
        impl_->projectionViews[eye].fov = views[eye].fov;
    }
    impl_->layerReady = true;
    return true;
}

const XrCompositionLayerBaseHeader* ImmersiveEnvironmentRenderer::layer() const {
    if (!impl_ || !impl_->layerReady)
        return nullptr;
    return reinterpret_cast<const XrCompositionLayerBaseHeader*>(&impl_->projectionLayer);
}

const XrCompositionLayerBaseHeader* ImmersiveEnvironmentRenderer::passthroughLayer() const {
    if (!impl_ || !impl_->passthroughActive ||
        impl_->requestedEnvironment.load(std::memory_order_acquire) != 4)
        return nullptr;
    return reinterpret_cast<const XrCompositionLayerBaseHeader*>(
        &impl_->passthroughCompositionLayer);
}

void ImmersiveEnvironmentRenderer::shutdown() {
    if (!impl_)
        return;
    impl_->layerReady = false;
    impl_->releaseRoom();
    impl_->stopPassthrough();
    if (impl_->program) glDeleteProgram(impl_->program);
    if (impl_->whiteTexture) glDeleteTextures(1, &impl_->whiteTexture);
    if (impl_->framebuffer) glDeleteFramebuffers(1, &impl_->framebuffer);
    if (impl_->depthBuffer) glDeleteRenderbuffers(1, &impl_->depthBuffer);
    impl_->program = 0;
    impl_->whiteTexture = 0;
    impl_->framebuffer = 0;
    impl_->depthBuffer = 0;
    for (int eye = 0; eye < 2; ++eye) {
        if (impl_->eyeSwapchains[eye] != XR_NULL_HANDLE && impl_->destroySwapchain) {
            impl_->destroySwapchain(impl_->eyeSwapchains[eye]);
            impl_->eyeSwapchains[eye] = XR_NULL_HANDLE;
        }
        impl_->eyeImages[eye].clear();
    }
    impl_->assets = nullptr;
    impl_->session = XR_NULL_HANDLE;
    impl_->instance = XR_NULL_HANDLE;
    impl_->appSpace = XR_NULL_HANDLE;
    impl_->createSwapchain = nullptr;
    impl_->enumerateSwapchainImages = nullptr;
    impl_->acquireSwapchainImage = nullptr;
    impl_->waitSwapchainImage = nullptr;
    impl_->releaseSwapchainImage = nullptr;
    impl_->destroySwapchain = nullptr;
    impl_->locateViews = nullptr;
    impl_->createPassthrough = nullptr;
    impl_->destroyPassthrough = nullptr;
    impl_->startPassthrough = nullptr;
    impl_->pausePassthrough = nullptr;
    impl_->createPassthroughLayer = nullptr;
    impl_->destroyPassthroughLayer = nullptr;
    impl_->pausePassthroughLayer = nullptr;
    impl_->resumePassthroughLayer = nullptr;
    impl_->functionsReady = false;
    impl_->passthroughFunctionsReady = false;
    impl_->passthroughFailed = false;
    impl_->requestedEnvironment.store(0, std::memory_order_release);
}
