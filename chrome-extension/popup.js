import { parseLegacyRedirectUrl } from "./account-id.mjs";

const openPsnButton = document.getElementById("open-psn");
const showQrButton = document.getElementById("show-qr");
const clearButton = document.getElementById("clear");
const statusElement = document.getElementById("status");
const redirectUrlInput = document.getElementById("redirect-url");
const resultSection = document.getElementById("result");
const qrCodeElement = document.getElementById("qr-code");
const LOGIN_TAB_KEY = "psnLoginTabId";
const REDIRECT_URL_KEY = "psnLoginRedirectUrl";

openPsnButton.addEventListener("click", openPsnRemotePlaySignIn);
showQrButton.addEventListener("click", showQrCode);
clearButton.addEventListener("click", clearQrCode);
redirectUrlInput.addEventListener("input", () => clearQrCode(true));

void restoreCapturedRedirect();

async function openPsnRemotePlaySignIn() {
  await chrome.storage.session.remove([LOGIN_TAB_KEY, REDIRECT_URL_KEY]);
  const tab = await chrome.tabs.create({ url: createPsnAuthorizeUrl() });
  await chrome.storage.session.set({ [LOGIN_TAB_KEY]: tab.id });
  window.close();
}

function showQrCode() {
  try {
    const redirectUrl = redirectUrlInput.value.trim();
    parseLegacyRedirectUrl(redirectUrl);
    renderQrCode(redirectUrl);
    showStatus("Sign-in QR code ready. Scan it in Horizon Stream immediately.");
  } catch (error) {
    resultSection.hidden = true;
    showStatus(error.message || "Paste a valid PS Remote Play redirect URL.", true);
  }
}

function clearQrCode(keepInput = false) {
  resultSection.hidden = true;
  qrCodeElement.replaceChildren();
  if (!keepInput && redirectUrlInput.value) showStatus("");
  void chrome.storage.session.remove(REDIRECT_URL_KEY);
}

function showStatus(message, isError = false) {
  statusElement.textContent = message;
  statusElement.classList.toggle("error", isError);
}

function createPsnAuthorizeUrl() {
  const url = new URL("https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize");
  url.search = new URLSearchParams({
    service_entity: "urn:service-entity:psn",
    response_type: "code",
    client_id: "ba495a24-818c-472b-b12d-ff231c1b5745",
    redirect_uri: "https://remoteplay.dl.playstation.net/remoteplay/redirect",
    scope: "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update",
    request_locale: "en_US",
    ui: "pr",
    service_logo: "ps",
    layout_type: "popup",
    smcid: "remoteplay",
    prompt: "always",
    PlatformPrivacyWs1: "minimal"
  }).toString();
  return url.toString();
}

async function restoreCapturedRedirect() {
  const { [REDIRECT_URL_KEY]: redirectUrl } = await chrome.storage.session.get(REDIRECT_URL_KEY);
  if (typeof redirectUrl !== "string") return;
  try {
    parseLegacyRedirectUrl(redirectUrl);
    redirectUrlInput.value = redirectUrl;
    renderQrCode(redirectUrl);
    showStatus("Sign-in redirect captured. QR code ready to scan in Horizon Stream.");
  } catch {
    await chrome.storage.session.remove(REDIRECT_URL_KEY);
  }
}

function renderQrCode(redirectUrl) {
  const qr = qrcode(0, "M");
  qr.addData(`HORIZONSTREAM:PSN_REDIRECT:${redirectUrl}`, "Byte");
  qr.make();
  qrCodeElement.innerHTML = qr.createSvgTag({
    cellSize: 8,
    margin: 16,
    scalable: true,
    title: "Horizon Stream PSN sign-in transfer code",
    alt: "Scan this QR code in Horizon Stream on Quest"
  });
  resultSection.hidden = false;
}
