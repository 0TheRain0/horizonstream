import assert from "node:assert/strict";
import test from "node:test";
import { parseLegacyRedirectUrl } from "../account-id.mjs";

test("accepts only a complete legacy PS Remote Play redirect URL", () => {
  const code = parseLegacyRedirectUrl(
    "https://remoteplay.dl.playstation.net/remoteplay/redirect?code=one-time-code&state=ignored"
  );
  assert.equal(code, "one-time-code");
  assert.throws(() => parseLegacyRedirectUrl("https://example.com/remoteplay/redirect?code=test"));
  assert.throws(() => parseLegacyRedirectUrl("https://remoteplay.dl.playstation.net/remoteplay/redirect"));
});
