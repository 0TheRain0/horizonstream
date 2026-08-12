const LEGACY_REDIRECT_ORIGIN = "https://remoteplay.dl.playstation.net";
const LEGACY_REDIRECT_PATH = "/remoteplay/redirect";

export function parseLegacyRedirectUrl(value) {
  let url;
  try {
    url = new URL(String(value || "").trim());
  } catch {
    throw new Error("Paste the complete redirect URL from the browser address bar.");
  }

  if (url.origin !== LEGACY_REDIRECT_ORIGIN || url.pathname !== LEGACY_REDIRECT_PATH) {
    throw new Error("This is not the PS Remote Play redirect URL. Return to step 1 and copy the final URL.");
  }

  const codes = url.searchParams.getAll("code");
  if (codes.length !== 1 || !codes[0]) {
    const error = url.searchParams.get("error");
    if (error) {
      throw new Error("Sony did not authorize the request. Return to step 1 and sign in again.");
    }
    throw new Error("The redirect URL did not contain an authorization code. Copy the complete final URL.");
  }
  return codes[0];
}
