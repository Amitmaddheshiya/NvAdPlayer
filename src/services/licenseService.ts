import AsyncStorage from "@react-native-async-storage/async-storage";

const LICENSE_KEY_STORAGE_KEY = "license_key_v1";
const LICENSE_DEVICE_STORAGE_KEY = "license_device_id_v1";
const LICENSE_GENERATOR_BASE_URLS = [
  "https://nva-signageplayertv-licences-fmza.vercel.app",
  "https://local-signage-player-tv-admin-user.vercel.app",
];
const LICENSE_TIMEOUT_MS = 15000;
const LICENSE_VERIFY_RETRIES = 3;

function normalizeKey(value: string): string {
  return String(value || "").trim().toUpperCase();
}

function hasConfiguredGeneratorUrl() {
  return LICENSE_GENERATOR_BASE_URLS.some((url) => /^https?:\/\//i.test(String(url || "")));
}

function fetchWithTimeout(url: string, timeoutMs = LICENSE_TIMEOUT_MS): Promise<Response> {
  return Promise.race([
    fetch(url, {
      headers: {
        "Cache-Control": "no-cache",
        Pragma: "no-cache",
      },
    }),
    new Promise<Response>((_, reject) =>
      setTimeout(() => reject(new Error("license-timeout")), timeoutMs)
    ),
  ]);
}

function wait(ms: number) {
  return new Promise<void>((resolve) => setTimeout(resolve, ms));
}

async function getExpectedLicenseFromServer(deviceId: string): Promise<string | null> {
  if (!hasConfiguredGeneratorUrl()) return null;
  const endpoints = LICENSE_GENERATOR_BASE_URLS
    .map((url) => String(url || "").trim().replace(/\/+$/, ""))
    .filter((url) => /^https?:\/\//i.test(url));

  for (const baseUrl of endpoints) {
    for (let attempt = 0; attempt < LICENSE_VERIFY_RETRIES; attempt += 1) {
      try {
        const res = await fetchWithTimeout(
          `${baseUrl}/api/generate?deviceId=${encodeURIComponent(deviceId)}`,
          LICENSE_TIMEOUT_MS + attempt * 4000
        );
        if (!res.ok) continue;
        const data = await res.json();
        const key = normalizeKey(String(data?.licenseKey || ""));
        if (key) return key;
      } catch (_e) {
      }
      if (attempt < LICENSE_VERIFY_RETRIES - 1) {
        await wait(1200 * (attempt + 1));
      }
    }
  }
  return null;
}

export async function readStoredLicense() {
  const [deviceId, licenseKey] = await Promise.all([
    AsyncStorage.getItem(LICENSE_DEVICE_STORAGE_KEY),
    AsyncStorage.getItem(LICENSE_KEY_STORAGE_KEY),
  ]);
  return {
    deviceId: String(deviceId || ""),
    licenseKey: normalizeKey(String(licenseKey || "")),
  };
}

export async function saveLicense(deviceId: string, licenseKey: string) {
  await Promise.all([
    AsyncStorage.setItem(LICENSE_DEVICE_STORAGE_KEY, String(deviceId)),
    AsyncStorage.setItem(LICENSE_KEY_STORAGE_KEY, normalizeKey(licenseKey)),
  ]);
}

export async function hasLocalActivationForDevice(deviceId: string) {
  const stored = await readStoredLicense();
  // Only check if we have a valid license key stored (4+ characters)
  // Ignore deviceId match since license is tied to device on server side
  return !!stored.licenseKey && stored.licenseKey.length >= 4;
}

export async function activateDeviceWithKey(deviceId: string, enteredKey: string) {
  const normalizedDeviceId = String(deviceId || "").trim();
  const normalizedKey = normalizeKey(enteredKey);

  if (!normalizedDeviceId) {
    return { success: false, message: "Device ID not found." };
  }
  if (!normalizedKey) {
    return { success: false, message: "Please enter license key." };
  }
  if (!hasConfiguredGeneratorUrl()) {
    return {
        success: false,
        message:
          "License server URL not configured. Set LICENSE_GENERATOR_BASE_URLS in app.",
    };
  }

  try {
    const stored = await readStoredLicense();
    if (stored.deviceId === normalizedDeviceId && stored.licenseKey === normalizedKey) {
      return { success: true, message: "Activation successful." };
    }
    const expectedKey = await getExpectedLicenseFromServer(normalizedDeviceId);
    if (!expectedKey) {
      return {
        success: false,
        message: "Unable to verify key right now. Check internet/license server and retry.",
      };
    }
    if (expectedKey !== normalizedKey) {
      return { success: false, message: "Invalid license key." };
    }

    await saveLicense(normalizedDeviceId, normalizedKey);
    return { success: true, message: "Activation successful." };
  } catch (e: any) {
    return {
      success: false,
      message: e?.message || "Activation failed. Try again.",
    };
  }
}
