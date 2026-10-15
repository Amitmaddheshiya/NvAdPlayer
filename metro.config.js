const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  watchFolders: [
    // Add src folder to watch folders for resolution
    require('path').resolve(__dirname, 'src'),
  ],
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
