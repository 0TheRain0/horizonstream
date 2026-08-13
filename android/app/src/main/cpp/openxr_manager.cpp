#include "openxr_manager.h"
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define LOG_TAG "HorizonStream_OpenXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static PFN_xrGetInstanceProcAddr g_pfnGetInstanceProcAddr = NULL;
static PFN_xrCreateInstance g_pfnCreateInstance = NULL;
static PFN_xrDestroyInstance g_pfnDestroyInstance = NULL;
static PFN_xrGetSystem g_pfnGetSystem = NULL;
static PFN_xrCreateSession g_pfnCreateSession = NULL;
static PFN_xrBeginSession g_pfnBeginSession = NULL;
static PFN_xrEndSession g_pfnEndSession = NULL;
static PFN_xrDestroySession g_pfnDestroySession = NULL;
static PFN_xrWaitFrame g_pfnWaitFrame = NULL;
static PFN_xrBeginFrame g_pfnBeginFrame = NULL;
static PFN_xrEndFrame g_pfnEndFrame = NULL;
static PFN_xrCreateReferenceSpace g_pfnCreateReferenceSpace = NULL;
static PFN_xrPollEvent g_pfnPollEvent = NULL;
static PFN_xrCreateSwapchain g_pfnCreateSwapchain = NULL;
static PFN_xrEnumerateSwapchainFormats g_pfnEnumerateSwapchainFormats = NULL;
static PFN_xrEnumerateSwapchainImages g_pfnEnumerateSwapchainImages = NULL;
static PFN_xrAcquireSwapchainImage g_pfnAcquireSwapchainImage = NULL;
static PFN_xrWaitSwapchainImage g_pfnWaitSwapchainImage = NULL;
static PFN_xrReleaseSwapchainImage g_pfnReleaseSwapchainImage = NULL;
static PFN_xrDestroySwapchain g_pfnDestroySwapchain = NULL;
static PFN_xrDestroySpace g_pfnDestroySpace = NULL;
static PFN_xrDestroyActionSet g_pfnDestroyActionSet = NULL;
static PFN_xrStringToPath g_pfnStringToPath = NULL;
static PFN_xrCreateActionSet g_pfnCreateActionSet = NULL;
static PFN_xrCreateAction g_pfnCreateAction = NULL;
static PFN_xrSuggestInteractionProfileBindings g_pfnSuggestInteractionProfileBindings = NULL;
static PFN_xrAttachSessionActionSets g_pfnAttachSessionActionSets = NULL;
static PFN_xrSyncActions g_pfnSyncActions = NULL;
static PFN_xrGetActionStateBoolean g_pfnGetActionStateBoolean = NULL;
static PFN_xrGetActionStateFloat g_pfnGetActionStateFloat = NULL;
static PFN_xrGetActionStateVector2f g_pfnGetActionStateVector2f = NULL;

static void* g_loaderHandle = NULL;
static XrInstance g_xrInstance = XR_NULL_HANDLE;
static XrSession g_xrSession = XR_NULL_HANDLE;
static XrSystemId g_systemId = XR_NULL_SYSTEM_ID;
static XrSessionState g_sessionState = XR_SESSION_STATE_UNKNOWN;
static XrSwapchain g_xrSwapchain = XR_NULL_HANDLE;
static XrSwapchain g_rightEyeSwapchain = XR_NULL_HANDLE;
static XrSpace g_appSpace = XR_NULL_HANDLE;
static bool g_xrInitialized = false;
static bool g_xrSessionRunning = false;
static bool g_xrSessionBegun = false;
static bool g_renderThreadStarted = false;
static bool g_loggedFirstCompositedFrame = false;
static bool g_loggedFirstRenderAttempt = false;
static bool g_loggedRenderPrerequisiteFailure = false;
static bool g_loggedSurfaceTextureFailure = false;
static int32_t g_streamWidth = 1280;
static int32_t g_streamHeight = 720;
static bool g_stereoConversionEnabled = false;
static float g_stereoDepthIntensity = 0.015f;
static JavaVM* g_javaVm = NULL;
static jobject g_activityObject = NULL;
static jmethodID g_controllerStateMethod = NULL;
static jobject g_surfaceTextureObject = NULL;
static jmethodID g_updateTexImageMethod = NULL;
static jmethodID g_getTransformMatrixMethod = NULL;
static jmethodID g_releaseSurfaceTextureMethod = NULL;
static jclass g_depthBridgeClass = NULL;
static jmethodID g_depthSubmitMethod = NULL;

static XrActionSet g_gameplayActionSet = XR_NULL_HANDLE;
static XrAction g_triggerAction = XR_NULL_HANDLE;
static XrAction g_squeezeAction = XR_NULL_HANDLE;
static XrAction g_thumbstickAction = XR_NULL_HANDLE;
static XrAction g_thumbstickClickAction = XR_NULL_HANDLE;
static XrAction g_aAction = XR_NULL_HANDLE;
static XrAction g_bAction = XR_NULL_HANDLE;
static XrAction g_xAction = XR_NULL_HANDLE;
static XrAction g_yAction = XR_NULL_HANDLE;
static XrAction g_menuAction = XR_NULL_HANDLE;
static XrPath g_leftHandPath = XR_NULL_PATH;
static XrPath g_rightHandPath = XR_NULL_PATH;

static EGLDisplay g_eglDisplay = EGL_NO_DISPLAY;
static EGLConfig g_eglConfig = NULL;
static EGLContext g_eglContext = EGL_NO_CONTEXT;
static EGLSurface g_eglSurface = EGL_NO_SURFACE;
static pthread_t g_renderThread;
static GLuint g_videoTexture = 0;
static GLuint g_stereoProgram = 0;
static GLuint g_stereoFramebuffer = 0;
static GLuint g_stereoVertexBuffer = 0;
static GLint g_positionLocation = -1;
static GLint g_textureCoordLocation = -1;
static GLint g_transformLocation = -1;
static GLint g_eyeSignLocation = -1;
static GLint g_depthIntensityLocation = -1;
static GLint g_depthTextureLocation = -1;
static GLint g_depthMapReadyLocation = -1;
static GLuint g_depthTexture = 0;
static GLuint g_depthCaptureProgram = 0;
static GLuint g_depthCaptureTexture = 0;
static GLuint g_depthCaptureFramebuffer = 0;
static GLint g_depthCapturePositionLocation = -1;
static GLint g_depthCaptureTextureCoordLocation = -1;
static GLint g_depthCaptureTransformLocation = -1;
static std::vector<uint8_t> g_depthCapturePixels;
static std::vector<uint8_t> g_pendingDepthMap;
static int32_t g_pendingDepthWidth = 0;
static int32_t g_pendingDepthHeight = 0;
static bool g_depthMapReady = false;
static std::atomic<bool> g_depthWorkerReady(false);
static std::atomic<bool> g_depthInferenceInFlight(false);
static std::mutex g_depthMapMutex;

static constexpr int32_t DEPTH_INPUT_WIDTH = 322;
static constexpr int32_t DEPTH_INPUT_HEIGHT = 182;
static GLuint g_settingsProgram = 0;
static GLuint g_settingsTexture = 0;
static GLint g_settingsPositionLocation = -1;
static GLint g_settingsTextureCoordLocation = -1;
static std::mutex g_settingsOverlayMutex;
static std::vector<uint8_t> g_settingsOverlayPixels;
static int32_t g_settingsOverlayWidth = 0;
static int32_t g_settingsOverlayHeight = 0;
static bool g_settingsOverlayVisible = false;
static bool g_settingsOverlayDirty = false;
static std::vector<XrSwapchainImageOpenGLESKHR> g_stereoSwapchainImages;
static std::vector<XrSwapchainImageOpenGLESKHR> g_rightEyeSwapchainImages;

static XrPath xrPath(const char* path) {
    XrPath result = XR_NULL_PATH;
    if (!g_pfnStringToPath ||
        XR_FAILED(g_pfnStringToPath(g_xrInstance, path, &result))) {
        LOGE("Failed to resolve OpenXR path: %s", path);
    }
    return result;
}

static bool createAction(
        XrAction* action,
        XrActionType type,
        const char* name,
        const char* localizedName,
        const XrPath* subactionPaths,
        uint32_t subactionPathCount) {
    XrActionCreateInfo info = { XR_TYPE_ACTION_CREATE_INFO };
    info.actionType = type;
    strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
    strncpy(info.localizedActionName, localizedName, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
    info.countSubactionPaths = subactionPathCount;
    info.subactionPaths = subactionPaths;
    XrResult result = g_pfnCreateAction(g_gameplayActionSet, &info, action);
    if (XR_FAILED(result)) {
        LOGE("xrCreateAction(%s) failed: %d", name, result);
        return false;
    }
    return true;
}

static bool initControllerActions() {
    if (!g_pfnStringToPath || !g_pfnCreateActionSet || !g_pfnCreateAction ||
        !g_pfnSuggestInteractionProfileBindings || !g_pfnAttachSessionActionSets) {
        LOGE("Required OpenXR action functions are unavailable.");
        return false;
    }

    g_leftHandPath = xrPath("/user/hand/left");
    g_rightHandPath = xrPath("/user/hand/right");
    XrPath hands[] = { g_leftHandPath, g_rightHandPath };
    if (g_leftHandPath == XR_NULL_PATH || g_rightHandPath == XR_NULL_PATH)
        return false;

    XrActionSetCreateInfo setInfo = { XR_TYPE_ACTION_SET_CREATE_INFO };
    strncpy(setInfo.actionSetName, "gameplay", XR_MAX_ACTION_SET_NAME_SIZE - 1);
    strncpy(setInfo.localizedActionSetName, "PlayStation Controls",
            XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
    setInfo.priority = 0;
    XrResult result = g_pfnCreateActionSet(g_xrInstance, &setInfo, &g_gameplayActionSet);
    if (XR_FAILED(result)) {
        LOGE("xrCreateActionSet failed: %d", result);
        return false;
    }

    bool created =
        createAction(&g_triggerAction, XR_ACTION_TYPE_FLOAT_INPUT, "trigger", "Triggers", hands, 2) &&
        createAction(&g_squeezeAction, XR_ACTION_TYPE_FLOAT_INPUT, "squeeze", "Grip Buttons", hands, 2) &&
        createAction(&g_thumbstickAction, XR_ACTION_TYPE_VECTOR2F_INPUT, "thumbstick", "Thumbsticks", hands, 2) &&
        createAction(&g_thumbstickClickAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "thumbstick_click", "Thumbstick Clicks", hands, 2) &&
        createAction(&g_aAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "a_button", "Cross", NULL, 0) &&
        createAction(&g_bAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "b_button", "Circle", NULL, 0) &&
        createAction(&g_xAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "x_button", "Square", NULL, 0) &&
        createAction(&g_yAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "y_button", "Triangle", NULL, 0) &&
        createAction(&g_menuAction, XR_ACTION_TYPE_BOOLEAN_INPUT, "menu_button", "Options", NULL, 0);
    if (!created)
        return false;

    XrActionSuggestedBinding bindings[] = {
        { g_triggerAction, xrPath("/user/hand/left/input/trigger/value") },
        { g_triggerAction, xrPath("/user/hand/right/input/trigger/value") },
        { g_squeezeAction, xrPath("/user/hand/left/input/squeeze/value") },
        { g_squeezeAction, xrPath("/user/hand/right/input/squeeze/value") },
        { g_thumbstickAction, xrPath("/user/hand/left/input/thumbstick") },
        { g_thumbstickAction, xrPath("/user/hand/right/input/thumbstick") },
        { g_thumbstickClickAction, xrPath("/user/hand/left/input/thumbstick/click") },
        { g_thumbstickClickAction, xrPath("/user/hand/right/input/thumbstick/click") },
        { g_aAction, xrPath("/user/hand/right/input/a/click") },
        { g_bAction, xrPath("/user/hand/right/input/b/click") },
        { g_xAction, xrPath("/user/hand/left/input/x/click") },
        { g_yAction, xrPath("/user/hand/left/input/y/click") },
        { g_menuAction, xrPath("/user/hand/left/input/menu/click") },
    };
    XrInteractionProfileSuggestedBinding suggested = {
        XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING
    };
    suggested.interactionProfile = xrPath("/interaction_profiles/oculus/touch_controller");
    suggested.countSuggestedBindings = sizeof(bindings) / sizeof(bindings[0]);
    suggested.suggestedBindings = bindings;
    result = g_pfnSuggestInteractionProfileBindings(g_xrInstance, &suggested);
    if (XR_FAILED(result)) {
        LOGE("Touch controller binding suggestion failed: %d", result);
        return false;
    }

    XrSessionActionSetsAttachInfo attachInfo = {
        XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO
    };
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &g_gameplayActionSet;
    result = g_pfnAttachSessionActionSets(g_xrSession, &attachInfo);
    if (XR_FAILED(result)) {
        LOGE("xrAttachSessionActionSets failed: %d", result);
        return false;
    }
    LOGI("Quest Touch controller actions initialized.");
    return true;
}

static float getFloatAction(XrAction action, XrPath hand) {
    XrActionStateGetInfo info = { XR_TYPE_ACTION_STATE_GET_INFO };
    info.action = action;
    info.subactionPath = hand;
    XrActionStateFloat state = { XR_TYPE_ACTION_STATE_FLOAT };
    return XR_SUCCEEDED(g_pfnGetActionStateFloat(g_xrSession, &info, &state)) && state.isActive
            ? state.currentState : 0.0f;
}

static XrVector2f getVectorAction(XrAction action, XrPath hand) {
    XrActionStateGetInfo info = { XR_TYPE_ACTION_STATE_GET_INFO };
    info.action = action;
    info.subactionPath = hand;
    XrActionStateVector2f state = { XR_TYPE_ACTION_STATE_VECTOR2F };
    if (XR_SUCCEEDED(g_pfnGetActionStateVector2f(g_xrSession, &info, &state)) && state.isActive)
        return state.currentState;
    return { 0.0f, 0.0f };
}

static bool getBooleanAction(XrAction action, XrPath hand = XR_NULL_PATH) {
    XrActionStateGetInfo info = { XR_TYPE_ACTION_STATE_GET_INFO };
    info.action = action;
    info.subactionPath = hand;
    XrActionStateBoolean state = { XR_TYPE_ACTION_STATE_BOOLEAN };
    return XR_SUCCEEDED(g_pfnGetActionStateBoolean(g_xrSession, &info, &state)) &&
           state.isActive && state.currentState;
}

static void updateControllerState(JNIEnv* env) {
    if (g_gameplayActionSet == XR_NULL_HANDLE || !g_pfnSyncActions ||
        !g_controllerStateMethod || !g_activityObject)
        return;

    XrActiveActionSet activeSet = { g_gameplayActionSet, XR_NULL_PATH };
    XrActionsSyncInfo syncInfo = { XR_TYPE_ACTIONS_SYNC_INFO };
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeSet;
    if (XR_FAILED(g_pfnSyncActions(g_xrSession, &syncInfo)))
        return;

    XrVector2f leftStick = getVectorAction(g_thumbstickAction, g_leftHandPath);
    XrVector2f rightStick = getVectorAction(g_thumbstickAction, g_rightHandPath);
    bool aPressed = getBooleanAction(g_aAction);
    bool bPressed = getBooleanAction(g_bAction);
    bool xPressed = getBooleanAction(g_xAction);
    bool yPressed = getBooleanAction(g_yAction);
    bool leftStickPressed = getBooleanAction(
        g_thumbstickClickAction, g_leftHandPath);
    bool rightStickPressed = getBooleanAction(
        g_thumbstickClickAction, g_rightHandPath);
    bool menuPressed = getBooleanAction(g_menuAction);
    uint32_t buttons = 0;
    if (aPressed) buttons |= 1u << 0; // Cross
    if (bPressed) buttons |= 1u << 1; // Circle
    if (xPressed && !menuPressed) buttons |= 1u << 2; // Square
    if (yPressed && !menuPressed) buttons |= 1u << 3; // Triangle
    if (leftStickPressed && !menuPressed) buttons |= 1u << 10; // L3
    if (rightStickPressed) buttons |= 1u << 11; // R3
    if (menuPressed && !xPressed && !yPressed && !leftStickPressed)
        buttons |= 1u << 12; // Options
    if (menuPressed && xPressed) buttons |= 1u << 13; // Share
    if (menuPressed && leftStickPressed) buttons |= 1u << 14; // Touchpad click
    if (menuPressed && yPressed) buttons |= 1u << 15; // PS

    env->CallVoidMethod(
        g_activityObject,
        g_controllerStateMethod,
        leftStick.x,
        leftStick.y,
        rightStick.x,
        rightStick.y,
        getFloatAction(g_triggerAction, g_leftHandPath),
        getFloatAction(g_triggerAction, g_rightHandPath),
        getFloatAction(g_squeezeAction, g_leftHandPath),
        getFloatAction(g_squeezeAction, g_rightHandPath),
        (jint)buttons);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Quest controller callback raised a Java exception.");
    }
}

static bool initOpenXRLoader() {
    if (g_pfnGetInstanceProcAddr != NULL) return true;

    g_pfnGetInstanceProcAddr = (PFN_xrGetInstanceProcAddr)dlsym(RTLD_DEFAULT, "xrGetInstanceProcAddr");
    if (!g_pfnGetInstanceProcAddr) {
        g_loaderHandle = dlopen("libopenxr_loader.so", RTLD_NOW | RTLD_GLOBAL);
        if (g_loaderHandle) {
            g_pfnGetInstanceProcAddr = (PFN_xrGetInstanceProcAddr)dlsym(g_loaderHandle, "xrGetInstanceProcAddr");
        }
    }

    if (!g_pfnGetInstanceProcAddr) {
        LOGW("OpenXR loader symbol xrGetInstanceProcAddr not accessible via dlsym.");
        return false;
    }
    LOGI("xrGetInstanceProcAddr symbol successfully resolved!");
    return true;
}

static bool initEGL() {
    g_eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_eglDisplay == EGL_NO_DISPLAY) return false;

    if (!eglInitialize(g_eglDisplay, NULL, NULL)) return false;

    const EGLint attribs[] = {
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_NONE
    };

    EGLint numConfigs;
    if (!eglChooseConfig(g_eglDisplay, attribs, &g_eglConfig, 1, &numConfigs) || numConfigs == 0) return false;

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    g_eglContext = eglCreateContext(g_eglDisplay, g_eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (g_eglContext == EGL_NO_CONTEXT) return false;

    const EGLint pbufferAttribs[] = {
        EGL_WIDTH, 16,
        EGL_HEIGHT, 16,
        EGL_NONE
    };
    g_eglSurface = eglCreatePbufferSurface(g_eglDisplay, g_eglConfig, pbufferAttribs);
    eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext);

    LOGI("EGL and GLES 3.0 context successfully initialized for OpenXR.");
    return true;
}

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[1024] = {};
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        LOGE("Stereo shader compilation failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static bool initStereoProgram() {
    static const char* vertexShaderSource =
        "attribute vec2 aPosition;\n"
        "attribute vec2 aTextureCoord;\n"
        "uniform mat4 uTextureTransform;\n"
        "varying vec2 vTextureCoord;\n"
        "varying vec2 vScreenCoord;\n"
        "void main() {\n"
        "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
        "  vTextureCoord = (uTextureTransform * vec4(aTextureCoord, 0.0, 1.0)).xy;\n"
        "  vScreenCoord = aTextureCoord;\n"
        "}\n";
    static const char* fragmentShaderSource =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision highp float;\n"
        "varying vec2 vTextureCoord;\n"
        "varying vec2 vScreenCoord;\n"
        "uniform samplerExternalOES uVideoTexture;\n"
        "uniform sampler2D uDepthTexture;\n"
        "uniform float uEyeSign;\n"
        "uniform float uDepthIntensity;\n"
        "uniform float uDepthMapReady;\n"
        "void main() {\n"
        "  float depth = uDepthMapReady > 0.5\n"
        "      ? texture2D(uDepthTexture, vScreenCoord).r : 0.5;\n"
        "  float disparity = (depth - 0.5) * uDepthIntensity * uEyeSign;\n"
        "  vec2 uv = clamp(vTextureCoord + vec2(disparity, 0.0), vec2(0.001), vec2(0.999));\n"
        "  vec3 color = texture2D(uVideoTexture, uv).rgb;\n"
        "  color = pow(max(color, vec3(0.0)), vec3(1.04));\n"
        "  color = (color - vec3(0.5)) * 1.06 + vec3(0.5);\n"
        "  float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));\n"
        "  color = mix(vec3(luma), color, 1.025);\n"
        "  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);\n"
        "}\n";

    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, vertexShaderSource);
    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentShaderSource);
    if (!vertexShader || !fragmentShader)
        return false;

    g_stereoProgram = glCreateProgram();
    glAttachShader(g_stereoProgram, vertexShader);
    glAttachShader(g_stereoProgram, fragmentShader);
    glLinkProgram(g_stereoProgram);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    GLint linked = GL_FALSE;
    glGetProgramiv(g_stereoProgram, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024] = {};
        glGetProgramInfoLog(g_stereoProgram, sizeof(log), NULL, log);
        LOGE("Stereo shader link failed: %s", log);
        return false;
    }

    g_positionLocation = glGetAttribLocation(g_stereoProgram, "aPosition");
    g_textureCoordLocation = glGetAttribLocation(g_stereoProgram, "aTextureCoord");
    g_transformLocation = glGetUniformLocation(g_stereoProgram, "uTextureTransform");
    g_eyeSignLocation = glGetUniformLocation(g_stereoProgram, "uEyeSign");
    g_depthIntensityLocation = glGetUniformLocation(g_stereoProgram, "uDepthIntensity");
    g_depthTextureLocation = glGetUniformLocation(g_stereoProgram, "uDepthTexture");
    g_depthMapReadyLocation = glGetUniformLocation(g_stereoProgram, "uDepthMapReady");
    glUseProgram(g_stereoProgram);
    glUniform1i(glGetUniformLocation(g_stereoProgram, "uVideoTexture"), 0);
    glUniform1i(g_depthTextureLocation, 1);
    glGenFramebuffers(1, &g_stereoFramebuffer);
    static const GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    };
    glGenBuffers(1, &g_stereoVertexBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, g_stereoVertexBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    // The video shader always has a sampler bound for the depth map. Binding
    // texture 0 while 2D-to-3D is disabled leaves that sampler incomplete on
    // Quest's GLES driver and may invalidate the entire draw, producing a black
    // quad. Keep a complete 1x1 neutral map for the flat immersive path.
    const uint8_t neutralDepth = 128;
    glGenTextures(1, &g_depthTexture);
    glBindTexture(GL_TEXTURE_2D, g_depthTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_R8, 1, 1, 0,
        GL_RED, GL_UNSIGNED_BYTE, &neutralDepth);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (g_stereoConversionEnabled) {
        static const char* depthCaptureFragmentShaderSource =
            "#extension GL_OES_EGL_image_external : require\n"
            "precision highp float;\n"
            "varying vec2 vTextureCoord;\n"
            "uniform samplerExternalOES uVideoTexture;\n"
            "void main() {\n"
            "  gl_FragColor = texture2D(uVideoTexture, vTextureCoord);\n"
            "}\n";
        GLuint captureVertexShader = compileShader(
            GL_VERTEX_SHADER, vertexShaderSource);
        GLuint captureFragmentShader = compileShader(
            GL_FRAGMENT_SHADER, depthCaptureFragmentShaderSource);
        if (!captureVertexShader || !captureFragmentShader)
            return false;
        g_depthCaptureProgram = glCreateProgram();
        glAttachShader(g_depthCaptureProgram, captureVertexShader);
        glAttachShader(g_depthCaptureProgram, captureFragmentShader);
        glLinkProgram(g_depthCaptureProgram);
        glDeleteShader(captureVertexShader);
        glDeleteShader(captureFragmentShader);
        glGetProgramiv(g_depthCaptureProgram, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) {
            char log[1024] = {};
            glGetProgramInfoLog(
                g_depthCaptureProgram, sizeof(log), NULL, log);
            LOGE("Depth capture shader link failed: %s", log);
            return false;
        }
        g_depthCapturePositionLocation = glGetAttribLocation(
            g_depthCaptureProgram, "aPosition");
        g_depthCaptureTextureCoordLocation = glGetAttribLocation(
            g_depthCaptureProgram, "aTextureCoord");
        g_depthCaptureTransformLocation = glGetUniformLocation(
            g_depthCaptureProgram, "uTextureTransform");
        glUseProgram(g_depthCaptureProgram);
        glUniform1i(
            glGetUniformLocation(g_depthCaptureProgram, "uVideoTexture"), 0);

        glGenTextures(1, &g_depthCaptureTexture);
        glBindTexture(GL_TEXTURE_2D, g_depthCaptureTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_RGBA8, DEPTH_INPUT_WIDTH, DEPTH_INPUT_HEIGHT,
            0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glGenFramebuffers(1, &g_depthCaptureFramebuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, g_depthCaptureFramebuffer);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
            g_depthCaptureTexture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) !=
            GL_FRAMEBUFFER_COMPLETE) {
            LOGE("Depth capture framebuffer is incomplete.");
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        glBindTexture(GL_TEXTURE_2D, g_depthTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(
            GL_TEXTURE_2D, 0, GL_R8, DEPTH_INPUT_WIDTH, DEPTH_INPUT_HEIGHT,
            0, GL_RED, GL_UNSIGNED_BYTE, NULL);
        glBindTexture(GL_TEXTURE_2D, 0);
        g_depthCapturePixels.resize(
            DEPTH_INPUT_WIDTH * DEPTH_INPUT_HEIGHT * 4);
        g_depthMapReady = false;
        LOGI("Depth Anything V2 capture initialized at %d x %d.",
             DEPTH_INPUT_WIDTH, DEPTH_INPUT_HEIGHT);
    }

    static const char* settingsVertexShaderSource =
        "attribute vec2 aPosition;\n"
        "attribute vec2 aTextureCoord;\n"
        "varying vec2 vTextureCoord;\n"
        "void main() {\n"
        "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
        "  vTextureCoord = vec2(aTextureCoord.x, 1.0 - aTextureCoord.y);\n"
        "}\n";
    static const char* settingsFragmentShaderSource =
        "precision mediump float;\n"
        "varying vec2 vTextureCoord;\n"
        "uniform sampler2D uSettingsTexture;\n"
        "void main() {\n"
        "  gl_FragColor = texture2D(uSettingsTexture, vTextureCoord);\n"
        "}\n";
    vertexShader = compileShader(GL_VERTEX_SHADER, settingsVertexShaderSource);
    fragmentShader = compileShader(
        GL_FRAGMENT_SHADER, settingsFragmentShaderSource);
    if (!vertexShader || !fragmentShader)
        return false;
    g_settingsProgram = glCreateProgram();
    glAttachShader(g_settingsProgram, vertexShader);
    glAttachShader(g_settingsProgram, fragmentShader);
    glLinkProgram(g_settingsProgram);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    glGetProgramiv(g_settingsProgram, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024] = {};
        glGetProgramInfoLog(g_settingsProgram, sizeof(log), NULL, log);
        LOGE("Settings overlay shader link failed: %s", log);
        return false;
    }
    g_settingsPositionLocation =
        glGetAttribLocation(g_settingsProgram, "aPosition");
    g_settingsTextureCoordLocation =
        glGetAttribLocation(g_settingsProgram, "aTextureCoord");
    glUseProgram(g_settingsProgram);
    glUniform1i(
        glGetUniformLocation(g_settingsProgram, "uSettingsTexture"), 1);
    glGenTextures(1, &g_settingsTexture);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, g_settingsTexture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glActiveTexture(GL_TEXTURE0);
    return true;
}

static void renderSettingsOverlay() {
    std::lock_guard<std::mutex> lock(g_settingsOverlayMutex);
    if (!g_settingsOverlayVisible || g_settingsOverlayPixels.empty() ||
        !g_settingsProgram || !g_settingsTexture)
        return;

    glUseProgram(g_settingsProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, g_settingsTexture);
    if (g_settingsOverlayDirty) {
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA,
            g_settingsOverlayWidth,
            g_settingsOverlayHeight,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            g_settingsOverlayPixels.data());
        g_settingsOverlayDirty = false;
    }
    glBindBuffer(GL_ARRAY_BUFFER, g_stereoVertexBuffer);
    glVertexAttribPointer(
        g_settingsPositionLocation,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(GLfloat),
        (void*)0);
    glEnableVertexAttribArray(g_settingsPositionLocation);
    glVertexAttribPointer(
        g_settingsTextureCoordLocation,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(GLfloat),
        (void*)(2 * sizeof(GLfloat)));
    glEnableVertexAttribArray(g_settingsTextureCoordLocation);

    // Keep the stream visible around the menu so settings feel like part of
    // the immersive session instead of launching a second Android window.
    glViewport(
        g_streamWidth / 8,
        g_streamHeight / 10,
        g_streamWidth * 3 / 4,
        g_streamHeight * 4 / 5);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisable(GL_BLEND);
    glActiveTexture(GL_TEXTURE0);
}

static void uploadPendingDepthMap() {
    std::vector<uint8_t> pending;
    int32_t width = 0;
    int32_t height = 0;
    {
        std::lock_guard<std::mutex> lock(g_depthMapMutex);
        if (g_pendingDepthMap.empty())
            return;
        pending.swap(g_pendingDepthMap);
        width = g_pendingDepthWidth;
        height = g_pendingDepthHeight;
        g_pendingDepthWidth = 0;
        g_pendingDepthHeight = 0;
    }
    if (!g_depthTexture || width <= 0 || height <= 0)
        return;

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, g_depthTexture);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_R8, width, height, 0, GL_RED,
        GL_UNSIGNED_BYTE, pending.data());
    glActiveTexture(GL_TEXTURE0);
    if (!g_depthMapReady)
        LOGI("First Depth Anything V2 map uploaded: %d x %d.", width, height);
    g_depthMapReady = true;
}

static void captureFrameForDepth(JNIEnv* env, const float* transform) {
    if (!g_stereoConversionEnabled ||
        !g_depthWorkerReady.load(std::memory_order_acquire) ||
        g_depthInferenceInFlight.load(std::memory_order_acquire) ||
        !g_depthCaptureProgram || !g_depthCaptureFramebuffer ||
        g_depthCapturePixels.empty() || !g_depthBridgeClass ||
        !g_depthSubmitMethod) {
        return;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, g_depthCaptureFramebuffer);
    glViewport(0, 0, DEPTH_INPUT_WIDTH, DEPTH_INPUT_HEIGHT);
    glUseProgram(g_depthCaptureProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_videoTexture);
    glUniformMatrix4fv(
        g_depthCaptureTransformLocation, 1, GL_FALSE, transform);
    glBindBuffer(GL_ARRAY_BUFFER, g_stereoVertexBuffer);
    glVertexAttribPointer(
        g_depthCapturePositionLocation,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(GLfloat),
        (void*)0);
    glEnableVertexAttribArray(g_depthCapturePositionLocation);
    glVertexAttribPointer(
        g_depthCaptureTextureCoordLocation,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(GLfloat),
        (void*)(2 * sizeof(GLfloat)));
    glEnableVertexAttribArray(g_depthCaptureTextureCoordLocation);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(
        0, 0, DEPTH_INPUT_WIDTH, DEPTH_INPUT_HEIGHT,
        GL_RGBA, GL_UNSIGNED_BYTE, g_depthCapturePixels.data());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    jobject buffer = env->NewDirectByteBuffer(
        g_depthCapturePixels.data(),
        static_cast<jlong>(g_depthCapturePixels.size()));
    if (!buffer)
        return;

    g_depthInferenceInFlight.store(true, std::memory_order_release);
    jboolean accepted = env->CallStaticBooleanMethod(
        g_depthBridgeClass,
        g_depthSubmitMethod,
        buffer,
        DEPTH_INPUT_WIDTH,
        DEPTH_INPUT_HEIGHT);
    env->DeleteLocalRef(buffer);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        accepted = JNI_FALSE;
    }
    if (accepted != JNI_TRUE)
        g_depthInferenceInFlight.store(false, std::memory_order_release);
}

static jobject createVideoSurfaceTexture(JNIEnv* env) {
    glGenTextures(1, &g_videoTexture);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_videoTexture);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    jclass surfaceTextureClass = env->FindClass("android/graphics/SurfaceTexture");
    jmethodID constructor = env->GetMethodID(surfaceTextureClass, "<init>", "(I)V");
    jobject localSurfaceTexture = env->NewObject(
        surfaceTextureClass, constructor, (jint)g_videoTexture);
    g_surfaceTextureObject = env->NewGlobalRef(localSurfaceTexture);
    jmethodID setDefaultBufferSize = env->GetMethodID(
        surfaceTextureClass, "setDefaultBufferSize", "(II)V");
    g_updateTexImageMethod = env->GetMethodID(surfaceTextureClass, "updateTexImage", "()V");
    g_getTransformMatrixMethod = env->GetMethodID(
        surfaceTextureClass, "getTransformMatrix", "([F)V");
    g_releaseSurfaceTextureMethod = env->GetMethodID(
        surfaceTextureClass, "release", "()V");
    env->CallVoidMethod(
        g_surfaceTextureObject,
        setDefaultBufferSize,
        (jint)g_streamWidth,
        (jint)g_streamHeight);

    jclass surfaceClass = env->FindClass("android/view/Surface");
    jmethodID surfaceConstructor = env->GetMethodID(
        surfaceClass, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
    return env->NewObject(surfaceClass, surfaceConstructor, localSurfaceTexture);
}

static bool createStereoSwapchain() {
    uint32_t formatCount = 0;
    if (XR_FAILED(g_pfnEnumerateSwapchainFormats(
            g_xrSession, 0, &formatCount, NULL)) || formatCount == 0)
        return false;
    std::vector<int64_t> formats(formatCount);
    if (XR_FAILED(g_pfnEnumerateSwapchainFormats(
            g_xrSession, formatCount, &formatCount, formats.data())))
        return false;

    int64_t selectedFormat = formats[0];
    for (int64_t format : formats) {
        if (format == GL_RGBA8) {
            selectedFormat = format;
            break;
        }
    }

    auto createEyeSwapchain = [&](XrSwapchain* swapchain,
                                  std::vector<XrSwapchainImageOpenGLESKHR>* images,
                                  const char* eyeName) {
        XrSwapchainCreateInfo info = { XR_TYPE_SWAPCHAIN_CREATE_INFO };
        info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                          XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        info.format = selectedFormat;
        info.sampleCount = 1;
        info.width = (uint32_t)g_streamWidth;
        info.height = (uint32_t)g_streamHeight;
        info.faceCount = 1;
        info.arraySize = 1;
        info.mipCount = 1;
        XrResult result = g_pfnCreateSwapchain(g_xrSession, &info, swapchain);
        if (XR_FAILED(result)) {
            LOGE("%s-eye xrCreateSwapchain failed: %d", eyeName, result);
            return false;
        }

        uint32_t imageCount = 0;
        if (XR_FAILED(g_pfnEnumerateSwapchainImages(
                *swapchain, 0, &imageCount, NULL)) || imageCount == 0)
            return false;
        images->resize(imageCount);
        for (auto& image : *images)
            image = { XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR };
        result = g_pfnEnumerateSwapchainImages(
            *swapchain,
            imageCount,
            &imageCount,
            reinterpret_cast<XrSwapchainImageBaseHeader*>(images->data()));
        if (XR_FAILED(result)) {
            LOGE("%s-eye xrEnumerateSwapchainImages failed: %d",
                 eyeName, result);
            return false;
        }
        return true;
    };

    if (!createEyeSwapchain(
            &g_xrSwapchain, &g_stereoSwapchainImages, "Left"))
        return false;
    if (g_stereoConversionEnabled &&
        !createEyeSwapchain(
            &g_rightEyeSwapchain, &g_rightEyeSwapchainImages, "Right"))
        return false;
    return initStereoProgram();
}

static bool renderStereoFrame(JNIEnv* env) {
    if (!g_loggedFirstRenderAttempt) {
        g_loggedFirstRenderAttempt = true;
        LOGI("First OpenXR video render attempt.");
    }
    if (!g_surfaceTextureObject || !g_updateTexImageMethod ||
        !g_getTransformMatrixMethod || g_stereoSwapchainImages.empty()) {
        if (!g_loggedRenderPrerequisiteFailure) {
            g_loggedRenderPrerequisiteFailure = true;
            LOGE("Video render prerequisites missing: surfaceTexture=%d update=%d "
                 "transform=%d swapchainImages=%zu.",
                 g_surfaceTextureObject != NULL,
                 g_updateTexImageMethod != NULL,
                 g_getTransformMatrixMethod != NULL,
                 g_stereoSwapchainImages.size());
        }
        return false;
    }

    env->CallVoidMethod(g_surfaceTextureObject, g_updateTexImageMethod);
    if (env->ExceptionCheck()) {
        if (!g_loggedSurfaceTextureFailure) {
            g_loggedSurfaceTextureFailure = true;
            LOGE("SurfaceTexture.updateTexImage() threw; decoded frames cannot "
                 "reach the OpenXR texture.");
            env->ExceptionDescribe();
        }
        env->ExceptionClear();
        return false;
    }

    jfloatArray transformArray = env->NewFloatArray(16);
    env->CallVoidMethod(
        g_surfaceTextureObject, g_getTransformMatrixMethod, transformArray);
    if (env->ExceptionCheck()) {
        LOGE("SurfaceTexture.getTransformMatrix() threw.");
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(transformArray);
        return false;
    }
    float transform[16];
    env->GetFloatArrayRegion(transformArray, 0, 16, transform);
    env->DeleteLocalRef(transformArray);

    if (g_stereoConversionEnabled) {
        uploadPendingDepthMap();
        captureFrameForDepth(env, transform);
    }

    glUseProgram(g_stereoProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_videoTexture);
    glUniformMatrix4fv(g_transformLocation, 1, GL_FALSE, transform);
    glUniform1f(g_depthIntensityLocation, g_stereoDepthIntensity);
    glBindBuffer(GL_ARRAY_BUFFER, g_stereoVertexBuffer);
    glVertexAttribPointer(
        g_positionLocation, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)0);
    glEnableVertexAttribArray(g_positionLocation);
    glVertexAttribPointer(
        g_textureCoordLocation, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat),
        (void*)(2 * sizeof(GLfloat)));
    glEnableVertexAttribArray(g_textureCoordLocation);

    auto renderEye = [&](XrSwapchain swapchain,
                         const std::vector<XrSwapchainImageOpenGLESKHR>& images,
                         float eyeSign,
                         const char* eyeName) {
        uint32_t imageIndex = 0;
        XrSwapchainImageAcquireInfo acquireInfo = {
            XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO
        };
        XrResult acquireResult = g_pfnAcquireSwapchainImage(
                swapchain, &acquireInfo, &imageIndex);
        if (XR_FAILED(acquireResult)) {
            LOGE("%s-eye xrAcquireSwapchainImage failed: %d.",
                 eyeName, acquireResult);
            return false;
        }
        XrSwapchainImageWaitInfo waitInfo = {
            XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO
        };
        waitInfo.timeout = XR_INFINITE_DURATION;
        XrResult waitResult = g_pfnWaitSwapchainImage(swapchain, &waitInfo);
        if (XR_FAILED(waitResult)) {
            LOGE("%s-eye xrWaitSwapchainImage failed: %d.",
                 eyeName, waitResult);
            return false;
        }

        glBindFramebuffer(GL_FRAMEBUFFER, g_stereoFramebuffer);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            images[imageIndex].image,
            0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("%s-eye framebuffer is incomplete.", eyeName);
            XrSwapchainImageReleaseInfo releaseInfo = {
                XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO
            };
            g_pfnReleaseSwapchainImage(swapchain, &releaseInfo);
            return false;
        }

        glViewport(0, 0, g_streamWidth, g_streamHeight);
        glUseProgram(g_stereoProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, g_videoTexture);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, g_depthTexture);
        glUniform1i(g_depthTextureLocation, 1);
        glUniform1f(
            g_depthMapReadyLocation, g_depthMapReady ? 1.0f : 0.0f);
        glActiveTexture(GL_TEXTURE0);
        glBindBuffer(GL_ARRAY_BUFFER, g_stereoVertexBuffer);
        glVertexAttribPointer(
            g_positionLocation,
            2,
            GL_FLOAT,
            GL_FALSE,
            4 * sizeof(GLfloat),
            (void*)0);
        glEnableVertexAttribArray(g_positionLocation);
        glVertexAttribPointer(
            g_textureCoordLocation,
            2,
            GL_FLOAT,
            GL_FALSE,
            4 * sizeof(GLfloat),
            (void*)(2 * sizeof(GLfloat)));
        glEnableVertexAttribArray(g_textureCoordLocation);
        glUniform1f(g_eyeSignLocation, eyeSign);
        // Discard a bounded number of stale errors so a lost context cannot
        // trap the render thread in an error-clearing loop.
        for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; ++i) { }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        GLenum drawError = glGetError();
        if (drawError != GL_NO_ERROR) {
            LOGE("%s-eye video draw failed with GLES error 0x%x.",
                 eyeName, drawError);
            XrSwapchainImageReleaseInfo releaseInfo = {
                XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO
            };
            g_pfnReleaseSwapchainImage(swapchain, &releaseInfo);
            return false;
        }
        renderSettingsOverlay();
        glFinish();

        XrSwapchainImageReleaseInfo releaseInfo = {
            XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO
        };
        return XR_SUCCEEDED(
            g_pfnReleaseSwapchainImage(swapchain, &releaseInfo));
    };

    // A near feature must move right in the left eye and left in the
    // right eye. Texture sampling moves a feature opposite the UV offset.
    if (!renderEye(
            g_xrSwapchain,
            g_stereoSwapchainImages,
            g_stereoConversionEnabled ? -1.0f : 0.0f,
            "Left"))
        return false;
    if (g_stereoConversionEnabled &&
        !renderEye(
            g_rightEyeSwapchain,
            g_rightEyeSwapchainImages,
            1.0f,
            "Right"))
        return false;
    if (!g_loggedFirstCompositedFrame) {
        g_loggedFirstCompositedFrame = true;
        LOGI("First video frame copied into the OpenXR swapchain.");
    }
    return true;
}

static void* openxrRenderLoopThread(void* arg) {
    LOGI("OpenXR 3D VR Render Thread active.");
    JNIEnv* env = NULL;
    bool attachedToJvm = g_javaVm &&
            g_javaVm->AttachCurrentThread(&env, NULL) == JNI_OK;
    if (!eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext)) {
        LOGE("Failed to make the OpenXR EGL context current on the render thread.");
    }

    while (g_xrSessionRunning) {
        if (g_xrSession != XR_NULL_HANDLE) {
            // Poll Events
            if (g_pfnPollEvent) {
                XrEventDataBuffer eventData;
                while (true) {
                    eventData.type = XR_TYPE_EVENT_DATA_BUFFER;
                    eventData.next = NULL;
                    if (g_pfnPollEvent(g_xrInstance, &eventData) == XR_SUCCESS) {
                        if (eventData.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                            XrEventDataSessionStateChanged* sessionStateChanged = (XrEventDataSessionStateChanged*)&eventData;
                            g_sessionState = sessionStateChanged->state;
                            LOGI("OpenXR Session State Changed: %d", g_sessionState);

                            if (g_sessionState == XR_SESSION_STATE_READY && !g_xrSessionBegun) {
                                XrSessionBeginInfo sessionBeginInfo = { XR_TYPE_SESSION_BEGIN_INFO, NULL, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO };
                                XrResult beginResult = g_pfnBeginSession(g_xrSession, &sessionBeginInfo);
                                if (XR_SUCCEEDED(beginResult)) {
                                    g_xrSessionBegun = true;
                                    LOGI("OpenXR session begun.");
                                } else {
                                    LOGE("xrBeginSession failed: %d", beginResult);
                                }
                            } else if (g_sessionState == XR_SESSION_STATE_STOPPING && g_xrSessionBegun) {
                                XrResult endResult = g_pfnEndSession(g_xrSession);
                                if (XR_SUCCEEDED(endResult)) {
                                    g_xrSessionBegun = false;
                                } else {
                                    LOGE("xrEndSession failed: %d", endResult);
                                }
                            } else if (g_sessionState == XR_SESSION_STATE_EXITING ||
                                       g_sessionState == XR_SESSION_STATE_LOSS_PENDING) {
                                g_xrSessionRunning = false;
                            }
                        }
                    } else {
                        break;
                    }
                }
            }

            // Render Frame if allowed
            // Quest may remain in READY until the application starts driving
            // the frame loop. Excluding READY deadlocks both sides: the app
            // waits for SYNCHRONIZED while the runtime waits for xrWaitFrame.
            bool sessionIsActive = g_xrSessionBegun &&
                                   (g_sessionState == XR_SESSION_STATE_READY ||
                                    g_sessionState == XR_SESSION_STATE_SYNCHRONIZED ||
                                    g_sessionState == XR_SESSION_STATE_VISIBLE ||
                                    g_sessionState == XR_SESSION_STATE_FOCUSED);

            if (sessionIsActive && g_pfnWaitFrame && g_pfnBeginFrame && g_pfnEndFrame) {
                if (attachedToJvm)
                    updateControllerState(env);

                XrFrameWaitInfo waitInfo = { XR_TYPE_FRAME_WAIT_INFO, NULL };
                XrFrameState frameState = { XR_TYPE_FRAME_STATE, NULL };
                g_pfnWaitFrame(g_xrSession, &waitInfo, &frameState);

                XrFrameBeginInfo beginInfo = { XR_TYPE_FRAME_BEGIN_INFO, NULL };
                g_pfnBeginFrame(g_xrSession, &beginInfo);

                bool videoFrameReady = frameState.shouldRender != XR_TRUE ||
                                       (attachedToJvm && renderStereoFrame(env));
                XrCompositionLayerQuad quadLayers[2];
                memset(quadLayers, 0, sizeof(quadLayers));
                XrCompositionLayerQuad& leftLayer = quadLayers[0];
                leftLayer.type = XR_TYPE_COMPOSITION_LAYER_QUAD;
                leftLayer.next = NULL;
                // MediaCodec produces opaque video. Enabling source-alpha blending
                // here can make decoder buffers with undefined alpha disappear.
                leftLayer.layerFlags = 0;
                leftLayer.space = g_appSpace;
                leftLayer.subImage.swapchain = g_xrSwapchain;
                leftLayer.subImage.imageRect.offset.x = 0;
                leftLayer.subImage.imageRect.offset.y = 0;
                leftLayer.subImage.imageRect.extent.width = g_streamWidth;
                leftLayer.subImage.imageRect.extent.height = g_streamHeight;
                leftLayer.subImage.imageArrayIndex = 0;
                leftLayer.pose.position.x = 0.0f;
                leftLayer.pose.position.y = 0.0f;
                leftLayer.pose.position.z = -2.5f;
                leftLayer.pose.orientation.w = 1.0f;
                leftLayer.size.width = 3.0f;
                leftLayer.size.height = 1.6875f;
                leftLayer.eyeVisibility = g_stereoConversionEnabled
                        ? XR_EYE_VISIBILITY_LEFT : XR_EYE_VISIBILITY_BOTH;

                XrCompositionLayerBaseHeader* layers[2] = {
                    reinterpret_cast<XrCompositionLayerBaseHeader*>(&leftLayer),
                    NULL
                };
                uint32_t contentLayerCount = 1;
                if (g_stereoConversionEnabled) {
                    XrCompositionLayerQuad& rightLayer = quadLayers[1];
                    rightLayer = leftLayer;
                    rightLayer.subImage.swapchain = g_rightEyeSwapchain;
                    rightLayer.subImage.imageRect.offset.x = 0;
                    rightLayer.eyeVisibility = XR_EYE_VISIBILITY_RIGHT;
                    layers[1] = reinterpret_cast<XrCompositionLayerBaseHeader*>(
                        &rightLayer);
                    contentLayerCount = 2;
                }

                XrFrameEndInfo endInfo = { XR_TYPE_FRAME_END_INFO, NULL };
                endInfo.displayTime = frameState.predictedDisplayTime;
                endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
                bool submitContent = frameState.shouldRender == XR_TRUE &&
                                     videoFrameReady;
                endInfo.layerCount = submitContent ? contentLayerCount : 0;
                endInfo.layers = submitContent
                        ? (const XrCompositionLayerBaseHeader* const*)&layers
                        : NULL;

                XrResult endFrameResult = g_pfnEndFrame(g_xrSession, &endInfo);
                if (XR_FAILED(endFrameResult)) {
                    LOGE("xrEndFrame failed: %d", endFrameResult);
                }
                continue;
            }
        }
        usleep(11000);
    }
    eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (attachedToJvm)
        g_javaVm->DetachCurrentThread();
    LOGI("OpenXR 3D VR Render Thread exiting.");
    return NULL;
}

extern "C" {

JNIEXPORT jobject JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeInitVR(
        JNIEnv* env, jobject thiz, jobject activity, jint stream_width, jint stream_height,
        jboolean stereo_conversion_enabled, jfloat stereo_depth_intensity) {
    if (g_xrInitialized) return NULL; // Should return existing surface if possible, but keeping simple

    if (stream_width <= 0 || stream_height <= 0) {
        LOGE("Invalid stream dimensions: %d x %d", stream_width, stream_height);
        return NULL;
    }
    g_streamWidth = stream_width;
    g_streamHeight = stream_height;
    g_stereoConversionEnabled = stereo_conversion_enabled == JNI_TRUE;
    g_stereoDepthIntensity = stereo_depth_intensity;
    g_depthWorkerReady.store(false, std::memory_order_release);
    g_depthInferenceInFlight.store(false, std::memory_order_release);
    g_depthMapReady = false;
    {
        std::lock_guard<std::mutex> lock(g_depthMapMutex);
        g_pendingDepthMap.clear();
        g_pendingDepthWidth = 0;
        g_pendingDepthHeight = 0;
    }

    if (!initOpenXRLoader()) {
        return NULL;
    }

    if (!initEGL()) {
        LOGE("Failed to initialize EGL for OpenXR.");
        return NULL;
    }

    env->GetJavaVM(&g_javaVm);
    g_activityObject = env->NewGlobalRef(activity);
    jclass activityClass = env->GetObjectClass(activity);
    g_controllerStateMethod = env->GetMethodID(
        activityClass,
        "onNativeControllerState",
        "(FFFFFFFFI)V");
    if (g_stereoConversionEnabled) {
        jclass localDepthBridge = env->FindClass(
            "com/cmsoft/horizonstream/depth/DepthAnythingV2Bridge");
        if (localDepthBridge) {
            g_depthBridgeClass = reinterpret_cast<jclass>(
                env->NewGlobalRef(localDepthBridge));
            g_depthSubmitMethod = env->GetStaticMethodID(
                g_depthBridgeClass,
                "submitFrame",
                "(Ljava/nio/ByteBuffer;II)Z");
            env->DeleteLocalRef(localDepthBridge);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        if (!g_depthBridgeClass || !g_depthSubmitMethod)
            LOGE("Unable to bind the Depth Anything V2 Kotlin bridge.");
    }
    env->DeleteLocalRef(activityClass);

    PFN_xrInitializeLoaderKHR pfnInitializeLoaderKHR = NULL;
    g_pfnGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction*)&pfnInitializeLoaderKHR);
    if (pfnInitializeLoaderKHR) {
        XrLoaderInitInfoAndroidKHR loaderInitInfo;
        memset(&loaderInitInfo, 0, sizeof(loaderInitInfo));
        loaderInitInfo.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
        loaderInitInfo.next = NULL;
        loaderInitInfo.applicationVM = (void*)g_javaVm;
        loaderInitInfo.applicationContext = (void*)g_activityObject;
        pfnInitializeLoaderKHR((const XrLoaderInitInfoBaseHeaderKHR*)&loaderInitInfo);
    }

    XrInstanceCreateInfoAndroidKHR androidCreateInfo;
    memset(&androidCreateInfo, 0, sizeof(androidCreateInfo));
    androidCreateInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidCreateInfo.next = NULL;
    androidCreateInfo.applicationVM = (void*)g_javaVm;
    androidCreateInfo.applicationActivity = (void*)g_activityObject;

    const char* extensions[] = {
        "XR_KHR_android_create_instance",
        "XR_KHR_opengl_es_enable",
        "XR_KHR_android_surface_swapchain"
    };

    XrInstanceCreateInfo createInfo;
    memset(&createInfo, 0, sizeof(createInfo));
    createInfo.type = XR_TYPE_INSTANCE_CREATE_INFO;
    createInfo.next = &androidCreateInfo;
    strncpy(createInfo.applicationInfo.applicationName, "Horizon Stream VR", XR_MAX_APPLICATION_NAME_SIZE - 1);
    createInfo.applicationInfo.applicationVersion = 1;
    strncpy(createInfo.applicationInfo.engineName, "Horizon Stream", XR_MAX_ENGINE_NAME_SIZE - 1);
    createInfo.applicationInfo.engineVersion = 1;
    createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;
    createInfo.enabledExtensionCount = 3;
    createInfo.enabledExtensionNames = extensions;

    g_pfnGetInstanceProcAddr(XR_NULL_HANDLE, "xrCreateInstance", (PFN_xrVoidFunction*)&g_pfnCreateInstance);
    if (!g_pfnCreateInstance) return NULL;

    XrResult res = g_pfnCreateInstance(&createInfo, &g_xrInstance);
    if (res != XR_SUCCESS || g_xrInstance == XR_NULL_HANDLE) {
        LOGE("Failed to create OpenXR Instance! Error code: %d", res);
        return NULL;
    }

    g_pfnGetInstanceProcAddr(g_xrInstance, "xrGetSystem", (PFN_xrVoidFunction*)&g_pfnGetSystem);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrCreateSession", (PFN_xrVoidFunction*)&g_pfnCreateSession);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrBeginSession", (PFN_xrVoidFunction*)&g_pfnBeginSession);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrEndSession", (PFN_xrVoidFunction*)&g_pfnEndSession);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrDestroySession", (PFN_xrVoidFunction*)&g_pfnDestroySession);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrDestroyInstance", (PFN_xrVoidFunction*)&g_pfnDestroyInstance);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrWaitFrame", (PFN_xrVoidFunction*)&g_pfnWaitFrame);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrBeginFrame", (PFN_xrVoidFunction*)&g_pfnBeginFrame);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrEndFrame", (PFN_xrVoidFunction*)&g_pfnEndFrame);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrCreateReferenceSpace", (PFN_xrVoidFunction*)&g_pfnCreateReferenceSpace);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrPollEvent", (PFN_xrVoidFunction*)&g_pfnPollEvent);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrCreateSwapchain", (PFN_xrVoidFunction*)&g_pfnCreateSwapchain);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrEnumerateSwapchainFormats", (PFN_xrVoidFunction*)&g_pfnEnumerateSwapchainFormats);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrEnumerateSwapchainImages", (PFN_xrVoidFunction*)&g_pfnEnumerateSwapchainImages);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrAcquireSwapchainImage", (PFN_xrVoidFunction*)&g_pfnAcquireSwapchainImage);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrWaitSwapchainImage", (PFN_xrVoidFunction*)&g_pfnWaitSwapchainImage);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrReleaseSwapchainImage", (PFN_xrVoidFunction*)&g_pfnReleaseSwapchainImage);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrDestroySwapchain", (PFN_xrVoidFunction*)&g_pfnDestroySwapchain);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrDestroySpace", (PFN_xrVoidFunction*)&g_pfnDestroySpace);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrDestroyActionSet", (PFN_xrVoidFunction*)&g_pfnDestroyActionSet);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrStringToPath", (PFN_xrVoidFunction*)&g_pfnStringToPath);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrCreateActionSet", (PFN_xrVoidFunction*)&g_pfnCreateActionSet);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrCreateAction", (PFN_xrVoidFunction*)&g_pfnCreateAction);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrSuggestInteractionProfileBindings", (PFN_xrVoidFunction*)&g_pfnSuggestInteractionProfileBindings);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrAttachSessionActionSets", (PFN_xrVoidFunction*)&g_pfnAttachSessionActionSets);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrSyncActions", (PFN_xrVoidFunction*)&g_pfnSyncActions);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrGetActionStateBoolean", (PFN_xrVoidFunction*)&g_pfnGetActionStateBoolean);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrGetActionStateFloat", (PFN_xrVoidFunction*)&g_pfnGetActionStateFloat);
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrGetActionStateVector2f", (PFN_xrVoidFunction*)&g_pfnGetActionStateVector2f);

    PFN_xrCreateSwapchainAndroidSurfaceKHR pfnCreateSwapchainAndroidSurfaceKHR = NULL;
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrCreateSwapchainAndroidSurfaceKHR", (PFN_xrVoidFunction*)&pfnCreateSwapchainAndroidSurfaceKHR);

    XrSystemGetInfo systemInfo = { XR_TYPE_SYSTEM_GET_INFO, NULL, XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY };
    if (g_pfnGetSystem(g_xrInstance, &systemInfo, &g_systemId) != XR_SUCCESS) return NULL;

    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding;
    memset(&graphicsBinding, 0, sizeof(graphicsBinding));
    graphicsBinding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    graphicsBinding.next = NULL;
    graphicsBinding.display = g_eglDisplay;
    graphicsBinding.config = g_eglConfig;
    graphicsBinding.context = g_eglContext;

    XrSessionCreateInfo sessionCreateInfo;
    memset(&sessionCreateInfo, 0, sizeof(sessionCreateInfo));
    sessionCreateInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    sessionCreateInfo.next = &graphicsBinding;
    sessionCreateInfo.systemId = g_systemId;

    PFN_xrGetOpenGLESGraphicsRequirementsKHR pfnGetOpenGLESGraphicsRequirementsKHR = NULL;
    g_pfnGetInstanceProcAddr(g_xrInstance, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction*)&pfnGetOpenGLESGraphicsRequirementsKHR);
    if (pfnGetOpenGLESGraphicsRequirementsKHR) {
        XrGraphicsRequirementsOpenGLESKHR graphicsRequirements = { XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR, NULL, 0, 0 };
        pfnGetOpenGLESGraphicsRequirementsKHR(g_xrInstance, g_systemId, &graphicsRequirements);
    }

    if (g_pfnCreateSession(g_xrInstance, &sessionCreateInfo, &g_xrSession) != XR_SUCCESS) {
        LOGE("Failed to create OpenXR GLES Session.");
        return NULL;
    }

    initControllerActions();

    XrReferenceSpaceCreateInfo spaceCreateInfo;
    memset(&spaceCreateInfo, 0, sizeof(spaceCreateInfo));
    spaceCreateInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    spaceCreateInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceCreateInfo.poseInReferenceSpace.orientation.w = 1.0f;
    g_pfnCreateReferenceSpace(g_xrSession, &spaceCreateInfo, &g_appSpace);

    // Always route decoded video through SurfaceTexture and an OpenGL
    // swapchain. Besides supporting stereo conversion, SurfaceTexture's
    // transform matrix normalizes Android decoder orientation on Quest.
    if (!g_pfnCreateSwapchain || !g_pfnEnumerateSwapchainFormats ||
        !g_pfnEnumerateSwapchainImages || !g_pfnAcquireSwapchainImage ||
        !g_pfnWaitSwapchainImage || !g_pfnReleaseSwapchainImage ||
        !createStereoSwapchain()) {
        LOGE("Failed to create the OpenXR video rendering pipeline.");
        return NULL;
    }
    jobject surfaceObj = createVideoSurfaceTexture(env);
    if (!surfaceObj) {
        LOGE("Failed to create the MediaCodec SurfaceTexture.");
        return NULL;
    }
    LOGI("%s OpenXR video pipeline initialized at %d x %d per eye.",
         g_stereoConversionEnabled ? "Stereo 2D-to-3D" : "Flat",
         g_streamWidth,
         g_streamHeight);

    LOGI("Native OpenXR Session created! Waiting for READY event to begin.");
    g_sessionState = XR_SESSION_STATE_UNKNOWN;
    g_xrSessionBegun = false;
    g_loggedFirstCompositedFrame = false;
    g_loggedFirstRenderAttempt = false;
    g_loggedRenderPrerequisiteFailure = false;
    g_loggedSurfaceTextureFailure = false;
    g_xrInitialized = true;
    // The render thread owns this context after initialization.
    eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    return surfaceObj;
}

JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeStartRenderLoop(JNIEnv* env, jobject thiz, jobject surface) {
    if (!g_xrSessionRunning && g_xrInitialized) {
        LOGI("Starting OpenXR 3D VR render thread.");
        g_xrSessionRunning = true;
        int createResult = pthread_create(&g_renderThread, NULL, openxrRenderLoopThread, NULL);
        if (createResult == 0) {
            g_renderThreadStarted = true;
        } else {
            g_xrSessionRunning = false;
            LOGE("Failed to start OpenXR render thread: %d", createResult);
        }
    }
}

JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeSetSettingsOverlay(
        JNIEnv* env,
        jobject thiz,
        jbyteArray rgba_pixels,
        jint width,
        jint height) {
    std::lock_guard<std::mutex> lock(g_settingsOverlayMutex);
    if (!rgba_pixels || width <= 0 || height <= 0) {
        g_settingsOverlayVisible = false;
        g_settingsOverlayDirty = false;
        g_settingsOverlayPixels.clear();
        return;
    }
    jsize length = env->GetArrayLength(rgba_pixels);
    size_t expectedLength =
        (size_t)width * (size_t)height * (size_t)4;
    if ((size_t)length != expectedLength) {
        LOGE("Invalid settings overlay pixel buffer: %d != %zu",
             length, expectedLength);
        return;
    }
    g_settingsOverlayPixels.resize(expectedLength);
    env->GetByteArrayRegion(
        rgba_pixels,
        0,
        length,
        reinterpret_cast<jbyte*>(g_settingsOverlayPixels.data()));
    g_settingsOverlayWidth = width;
    g_settingsOverlayHeight = height;
    g_settingsOverlayDirty = true;
    g_settingsOverlayVisible = true;
    LOGI("Immersive settings overlay updated: %d x %d.", width, height);
}

JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeSetDepthPipelineReady(
        JNIEnv* env,
        jobject thiz,
        jboolean ready) {
    (void)env;
    (void)thiz;
    g_depthWorkerReady.store(
        ready == JNI_TRUE, std::memory_order_release);
    LOGI("Depth Anything V2 worker ready: %s.",
         ready == JNI_TRUE ? "true" : "false");
}

JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeSetDepthMap(
        JNIEnv* env,
        jobject thiz,
        jbyteArray depth_map,
        jint width,
        jint height) {
    (void)thiz;
    if (!depth_map || width != DEPTH_INPUT_WIDTH ||
        height != DEPTH_INPUT_HEIGHT) {
        g_depthInferenceInFlight.store(false, std::memory_order_release);
        return;
    }
    const size_t expected = static_cast<size_t>(width) * height;
    if (static_cast<size_t>(env->GetArrayLength(depth_map)) < expected) {
        LOGE("Rejected an undersized Depth Anything V2 map.");
        g_depthInferenceInFlight.store(false, std::memory_order_release);
        return;
    }

    std::vector<uint8_t> copy(expected);
    env->GetByteArrayRegion(
        depth_map,
        0,
        static_cast<jsize>(expected),
        reinterpret_cast<jbyte*>(copy.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        g_depthInferenceInFlight.store(false, std::memory_order_release);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_depthMapMutex);
        g_pendingDepthMap.swap(copy);
        g_pendingDepthWidth = width;
        g_pendingDepthHeight = height;
    }
    g_depthInferenceInFlight.store(false, std::memory_order_release);
}

JNIEXPORT void JNICALL Java_com_cmsoft_horizonstream_stream_VRStreamActivity_nativeStopVR(JNIEnv* env, jobject thiz) {
    LOGI("Stopping Native OpenXR 3D VR Session.");
    g_xrSessionRunning = false;
    if (g_renderThreadStarted) {
        pthread_join(g_renderThread, NULL);
        g_renderThreadStarted = false;
    }
    if (g_surfaceTextureObject && g_releaseSurfaceTextureMethod) {
        env->CallVoidMethod(g_surfaceTextureObject, g_releaseSurfaceTextureMethod);
        env->DeleteGlobalRef(g_surfaceTextureObject);
        g_surfaceTextureObject = NULL;
    }
    if (g_eglDisplay != EGL_NO_DISPLAY && g_eglContext != EGL_NO_CONTEXT) {
        eglMakeCurrent(g_eglDisplay, g_eglSurface, g_eglSurface, g_eglContext);
        if (g_stereoVertexBuffer) glDeleteBuffers(1, &g_stereoVertexBuffer);
        if (g_stereoFramebuffer) glDeleteFramebuffers(1, &g_stereoFramebuffer);
        if (g_stereoProgram) glDeleteProgram(g_stereoProgram);
        if (g_depthCaptureProgram) glDeleteProgram(g_depthCaptureProgram);
        if (g_settingsProgram) glDeleteProgram(g_settingsProgram);
        if (g_depthCaptureFramebuffer)
            glDeleteFramebuffers(1, &g_depthCaptureFramebuffer);
        if (g_videoTexture) glDeleteTextures(1, &g_videoTexture);
        if (g_depthTexture) glDeleteTextures(1, &g_depthTexture);
        if (g_depthCaptureTexture)
            glDeleteTextures(1, &g_depthCaptureTexture);
        if (g_settingsTexture) glDeleteTextures(1, &g_settingsTexture);
        g_stereoVertexBuffer = 0;
        g_stereoFramebuffer = 0;
        g_stereoProgram = 0;
        g_depthCaptureProgram = 0;
        g_settingsProgram = 0;
        g_depthCaptureFramebuffer = 0;
        g_videoTexture = 0;
        g_depthTexture = 0;
        g_depthCaptureTexture = 0;
        g_settingsTexture = 0;
        eglMakeCurrent(g_eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    if (g_xrSession != XR_NULL_HANDLE && g_pfnEndSession) {
        if (g_xrSessionBegun) {
            g_pfnEndSession(g_xrSession);
            g_xrSessionBegun = false;
        }
        if (g_xrSwapchain != XR_NULL_HANDLE && g_pfnDestroySwapchain) {
            g_pfnDestroySwapchain(g_xrSwapchain);
            g_xrSwapchain = XR_NULL_HANDLE;
        }
        if (g_rightEyeSwapchain != XR_NULL_HANDLE && g_pfnDestroySwapchain) {
            g_pfnDestroySwapchain(g_rightEyeSwapchain);
            g_rightEyeSwapchain = XR_NULL_HANDLE;
        }
        if (g_appSpace != XR_NULL_HANDLE && g_pfnDestroySpace) {
            g_pfnDestroySpace(g_appSpace);
            g_appSpace = XR_NULL_HANDLE;
        }
        if (g_pfnDestroySession) g_pfnDestroySession(g_xrSession);
        g_xrSession = XR_NULL_HANDLE;
    }
    if (g_gameplayActionSet != XR_NULL_HANDLE && g_pfnDestroyActionSet) {
        g_pfnDestroyActionSet(g_gameplayActionSet);
        g_gameplayActionSet = XR_NULL_HANDLE;
    }
    if (g_xrInstance != XR_NULL_HANDLE && g_pfnDestroyInstance) {
        g_pfnDestroyInstance(g_xrInstance);
        g_xrInstance = XR_NULL_HANDLE;
    }
    if (g_activityObject) {
        env->DeleteGlobalRef(g_activityObject);
        g_activityObject = NULL;
    }
    g_controllerStateMethod = NULL;
    if (g_depthBridgeClass) {
        env->DeleteGlobalRef(g_depthBridgeClass);
        g_depthBridgeClass = NULL;
    }
    g_depthSubmitMethod = NULL;
    g_depthWorkerReady.store(false, std::memory_order_release);
    g_depthInferenceInFlight.store(false, std::memory_order_release);
    g_depthMapReady = false;
    g_depthCapturePixels.clear();
    {
        std::lock_guard<std::mutex> lock(g_depthMapMutex);
        g_pendingDepthMap.clear();
        g_pendingDepthWidth = 0;
        g_pendingDepthHeight = 0;
    }
    {
        std::lock_guard<std::mutex> lock(g_settingsOverlayMutex);
        g_settingsOverlayPixels.clear();
        g_settingsOverlayVisible = false;
        g_settingsOverlayDirty = false;
        g_settingsOverlayWidth = 0;
        g_settingsOverlayHeight = 0;
    }
    g_stereoSwapchainImages.clear();
    g_rightEyeSwapchainImages.clear();
    g_updateTexImageMethod = NULL;
    g_getTransformMatrixMethod = NULL;
    g_releaseSurfaceTextureMethod = NULL;
    if (g_eglDisplay != EGL_NO_DISPLAY) {
        if (g_eglSurface != EGL_NO_SURFACE)
            eglDestroySurface(g_eglDisplay, g_eglSurface);
        if (g_eglContext != EGL_NO_CONTEXT)
            eglDestroyContext(g_eglDisplay, g_eglContext);
        eglTerminate(g_eglDisplay);
    }
    g_eglSurface = EGL_NO_SURFACE;
    g_eglContext = EGL_NO_CONTEXT;
    g_eglDisplay = EGL_NO_DISPLAY;
    g_loggedFirstCompositedFrame = false;
    g_loggedFirstRenderAttempt = false;
    g_loggedRenderPrerequisiteFailure = false;
    g_loggedSurfaceTextureFailure = false;
    g_xrInitialized = false;
}

}
