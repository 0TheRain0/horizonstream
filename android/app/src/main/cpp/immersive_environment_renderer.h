#ifndef HORIZON_STREAM_IMMERSIVE_ENVIRONMENT_RENDERER_H
#define HORIZON_STREAM_IMMERSIVE_ENVIRONMENT_RENDERER_H

#include <openxr/openxr.h>
#include <cstdint>

struct AAssetManager;

class ImmersiveEnvironmentRenderer {
public:
    ImmersiveEnvironmentRenderer();
    ~ImmersiveEnvironmentRenderer();

    bool initialize(AAssetManager* assets,
                    XrInstance instance,
                    XrSession session,
                    XrSpace appSpace,
                    PFN_xrGetInstanceProcAddr getProc,
                    int64_t colorFormat,
                    bool passthroughEnabled);
    void setEnvironment(int environment);
    void pausePassthrough();
    bool render(XrTime displayTime);
    const XrCompositionLayerBaseHeader* layer() const;
    const XrCompositionLayerBaseHeader* passthroughLayer() const;
    void shutdown();

private:
    struct Impl;
    Impl* impl_;
};

#endif
