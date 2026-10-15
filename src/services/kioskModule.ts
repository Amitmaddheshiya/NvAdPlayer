import { NativeModules } from "react-native";

const { KioskModule } = NativeModules as any;

export function enableKioskMode() {
  if (KioskModule?.enableKioskMode) {
    KioskModule.enableKioskMode();
  }
}

export function disableKioskMode() {
  if (KioskModule?.disableKioskMode) {
    KioskModule.disableKioskMode();
  }
}

export function setAsHomeLauncher() {
  if (KioskModule?.setAsHomeLauncher) {
    KioskModule.setAsHomeLauncher();
  }
}

export function isKioskModuleAvailable() {
  return !!KioskModule;
}
