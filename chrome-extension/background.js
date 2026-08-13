import { parseLegacyRedirectUrl } from "./account-id.mjs";

const LOGIN_TAB_KEY = "psnLoginTabId";
const REDIRECT_URL_KEY = "psnLoginRedirectUrl";

function configureSidePanel() {
  return chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true });
}

chrome.runtime.onInstalled.addListener(() => {
  void configureSidePanel();
});

chrome.runtime.onStartup.addListener(() => {
  void configureSidePanel();
});

void configureSidePanel();

chrome.webNavigation.onCommitted.addListener(async (details) => {
  if (details.frameId !== 0) return;

  const { [LOGIN_TAB_KEY]: loginTabId } = await chrome.storage.session.get(LOGIN_TAB_KEY);
  if (details.tabId !== loginTabId) return;

  try {
    parseLegacyRedirectUrl(details.url);
  } catch {
    return;
  }

  await chrome.storage.session.set({ [REDIRECT_URL_KEY]: details.url });
  await chrome.storage.session.remove(LOGIN_TAB_KEY);
}, {
  url: [{
    schemes: ["https"],
    hostEquals: "remoteplay.dl.playstation.net",
    pathPrefix: "/remoteplay/redirect"
  }]
});
