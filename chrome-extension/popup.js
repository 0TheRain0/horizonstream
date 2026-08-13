import { parseLegacyRedirectUrl } from "./account-id.mjs";

const openPsnButton = document.getElementById("open-psn");
const showQrButton = document.getElementById("show-qr");
const clearButton = document.getElementById("clear");
const copyUrlButton = document.getElementById("copy-url");
const statusElement = document.getElementById("status");
const resultStatusElement = document.getElementById("result-status");
const redirectUrlInput = document.getElementById("redirect-url");
const capturedUrlInput = document.getElementById("captured-url");
const setupSection = document.getElementById("setup");
const resultSection = document.getElementById("result");
const qrCodeElement = document.getElementById("qr-code");
const LOGIN_TAB_KEY = "psnLoginTabId";
const REDIRECT_URL_KEY = "psnLoginRedirectUrl";

openPsnButton.addEventListener("click", openPsnRemotePlaySignIn);
showQrButton.addEventListener("click", showQrCode);
clearButton.addEventListener("click", clearQrCode);
copyUrlButton.addEventListener("click", copyCapturedRedirectUrl);
redirectUrlInput.addEventListener("input", () => clearQrCode(true));

void restoreCapturedRedirect();

async function openPsnRemotePlaySignIn() {
  await chrome.storage.session.remove([LOGIN_TAB_KEY, REDIRECT_URL_KEY]);
  resetToSetup();
  const tab = await chrome.tabs.create({
    url: createPsnAuthorizeUrl(),
    active: false
  });
  await chrome.storage.session.set({ [LOGIN_TAB_KEY]: tab.id });
  await chrome.tabs.update(tab.id, { active: true });
  showStatus("Sign-in tab opened. Keep this panel visible; the Horizon Stream QR code will appear here after sign-in.");
}

function showQrCode() {
  try {
    const redirectUrl = redirectUrlInput.value.trim();
    parseLegacyRedirectUrl(redirectUrl);
    renderQrCode(redirectUrl);
    showStatus("Horizon Stream sign-in QR code ready. Scan it immediately.", false, true);
  } catch (error) {
    resultSection.hidden = true;
    showStatus(error.message || "Paste a valid PS Remote Play redirect URL.", true);
  }
}

function clearQrCode(keepInput = false) {
  resetToSetup(keepInput);
  void chrome.storage.session.remove(REDIRECT_URL_KEY);
}

function resetToSetup(keepInput = false) {
  setupSection.hidden = false;
  resultSection.hidden = true;
  qrCodeElement.replaceChildren();
  capturedUrlInput.value = "";
  resultStatusElement.textContent = "";
  resultStatusElement.classList.remove("error");
  if (!keepInput) {
    redirectUrlInput.value = "";
    showStatus("");
  }
  setupSection.scrollIntoView({ block: "start", behavior: "smooth" });
}

function showStatus(message, isError = false, result = false) {
  const element = result ? resultStatusElement : statusElement;
  element.textContent = message;
  element.classList.toggle("error", isError);
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
    showStatus("Sign-in redirect captured. Horizon Stream QR code ready to scan.", false, true);
  } catch {
    await chrome.storage.session.remove(REDIRECT_URL_KEY);
  }
}

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== "session") return;
  const redirectUrl = changes[REDIRECT_URL_KEY]?.newValue;
  if (typeof redirectUrl !== "string") return;
  try {
    parseLegacyRedirectUrl(redirectUrl);
    redirectUrlInput.value = redirectUrl;
    renderQrCode(redirectUrl);
    showStatus("Sign-in redirect captured. Horizon Stream QR code ready to scan.", false, true);
  } catch {
    showStatus("A captured sign-in redirect was invalid. Start the sign-in again.", true);
  }
});

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
  capturedUrlInput.value = redirectUrl;
  setupSection.hidden = true;
  resultSection.hidden = false;
  resultSection.scrollIntoView({ block: "start", behavior: "smooth" });
}

async function copyCapturedRedirectUrl() {
  const redirectUrl = capturedUrlInput.value;
  if (!redirectUrl) return;
  try {
    await navigator.clipboard.writeText(redirectUrl);
    showStatus("Redirect URL copied. Treat it like a password.", false, true);
  } catch {
    capturedUrlInput.focus();
    capturedUrlInput.select();
    showStatus("Select and copy the redirect URL manually.", false, true);
  }
}
