import { NativeEventEmitter, NativeModules } from "react-native";

const { UsbManagerModule } = NativeModules as any;

export type UsbState = {
  mounted: boolean;
  hasPlayableMedia: boolean;
  mountPath: string;
  mountPaths: string[];
  playlist: any[];
  reason?: string;
};

const EMPTY_USB_STATE: UsbState = {
  mounted: false,
  hasPlayableMedia: false,
  mountPath: "",
  mountPaths: [],
  playlist: [],
};

function normalizeUsbState(value: any): UsbState {
  return {
    mounted: !!value?.mounted,
    hasPlayableMedia: !!value?.hasPlayableMedia,
    mountPath: String(value?.mountPath || ""),
    mountPaths: Array.isArray(value?.mountPaths)
      ? value.mountPaths.map((entry: any) => String(entry || "")).filter(Boolean)
      : [],
    playlist: Array.isArray(value?.playlist) ? value.playlist : [],
    reason: value?.reason ? String(value.reason) : undefined,
  };
}

export function isUsbModuleAvailable() {
  return !!UsbManagerModule;
}

export async function refreshUsbState(): Promise<UsbState> {
  if (!UsbManagerModule?.refreshUsbState) return EMPTY_USB_STATE;
  const result = await UsbManagerModule.refreshUsbState();
  return normalizeUsbState(result);
}

export async function getCurrentUsbState(): Promise<UsbState> {
  if (!UsbManagerModule?.getCurrentUsbState) return EMPTY_USB_STATE;
  const result = await UsbManagerModule.getCurrentUsbState();
  return normalizeUsbState(result);
}

export function subscribeUsbState(listener: (state: UsbState) => void) {
  if (!UsbManagerModule) {
    return () => {};
  }
  const emitter = new NativeEventEmitter(UsbManagerModule);
  const subscription = emitter.addListener("usbMediaStateChanged", (event: any) => {
    listener(normalizeUsbState(event));
  });
  return () => subscription.remove();
}
