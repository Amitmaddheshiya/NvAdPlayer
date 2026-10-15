import React, { useEffect, useState, useRef, useCallback } from 'react';
import {
  StyleSheet,
  View,
  Text,
  Image,
  Pressable,
  BackHandler,
  ActivityIndicator,
  StatusBar,
  AppState,
  NativeModules,
  TextInput,
  ScrollView,
  Animated,
  Easing,
  PermissionsAndroid,
  Alert,
  Linking,
  Dimensions,
  useWindowDimensions,
  NativeEventEmitter,
  NativeModules as RNNativeModules,
  ViewStyle,
} from 'react-native';
import RNFS from 'react-native-fs';
import Video from 'react-native-video';
import { WebView } from 'react-native-webview';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {
  isUsbModuleAvailable,
  getCurrentUsbState,
  subscribeUsbState,
  type UsbState,
} from './src/services/usbManagerModule';
import {
  enableKioskMode,
  disableKioskMode,
  isKioskModuleAvailable,
} from './src/services/kioskModule';
import {
  activateDeviceWithKey,
  hasLocalActivationForDevice,
  readStoredLicense,
} from './src/services/licenseService';

const { PermissionModule } = NativeModules;

// @ts-ignore
const TVEventHandler = require('react-native').TVEventHandler;

// Path to target nvsign directory in internal storage (/sdcard/nvsign)
const ADS_DIR = `${RNFS.ExternalStorageDirectoryPath}/nvsign`;

interface MediaItem {
  name: string;
  path: string;
  type: 'image' | 'video';
}

interface SectionData {
  id: string;
  title: string;
  files: MediaItem[];
}

type MediaTransition = 'none' | 'fade' | 'slide_left' | 'slide_right' | 'slide_up' | 'slide_down' | 'zoom_in' | 'zoom_out' | 'flip' | 'rotate';


import { requireNativeComponent } from 'react-native';

const NativeExoPlayer = requireNativeComponent<any>('NativeVideoPlayerView');
const NativeAndroidMediaPlayer = requireNativeComponent<any>('AndroidNativeMediaPlayerView');
const NativeChromiumPlayer = requireNativeComponent<any>('SecondaryWebViewPlayer');

interface SectionPlayerProps {
  section: SectionData;
  sectionIndex: number;
  totalSections: number;
  config: AppConfig;
  configOpen: boolean;
  onOpenConfig: () => void;
}

const MediaTransitionView = ({ transition, changeKey, children }: { transition: MediaTransition; changeKey: string; children: React.ReactNode }) => {
  const progress = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    progress.stopAnimation();
    progress.setValue(0);
    if (transition === 'none') {
      progress.setValue(1);
      return;
    }
    const animation = Animated.timing(progress, {
      toValue: 1,
      duration: 520,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    });
    animation.start();
    return () => animation.stop();
  }, [changeKey, progress, transition]);

  const from = (value: number, fallback = 0) => progress.interpolate({ inputRange: [0, 1], outputRange: [value, fallback] });
  const transform: any[] = [];
  if (transition === 'slide_left') transform.push({ translateX: from(1200) });
  if (transition === 'slide_right') transform.push({ translateX: from(-1200) });
  if (transition === 'slide_up') transform.push({ translateY: from(700) });
  if (transition === 'slide_down') transform.push({ translateY: from(-700) });
  if (transition === 'zoom_in') transform.push({ scale: from(0.72, 1) });
  if (transition === 'zoom_out') transform.push({ scale: from(1.32, 1) });
  if (transition === 'flip') transform.push({ perspective: 1200 }, { rotateY: from(80, 0).interpolate({ inputRange: [0, 1], outputRange: ['80deg', '0deg'] }) });
  if (transition === 'rotate') transform.push({ perspective: 1200 }, { rotate: from(-12, 0).interpolate({ inputRange: [0, 1], outputRange: ['-12deg', '0deg'] }) }, { scale: from(0.85, 1) });

  return (
    <Animated.View style={[styles.transitionFrame, { opacity: transition === 'fade' ? progress : 1, transform }]}>
      {children}
    </Animated.View>
  );
};

const SectionPlayer: React.FC<SectionPlayerProps> = ({
  section,
  sectionIndex,
  totalSections,
  config,
  configOpen,
  onOpenConfig,
}) => {
  const [currentIndex, setCurrentIndex] = useState<number>(0);
  const [, setErrorCount] = useState<number>(0);
  const [reloadToken, setReloadToken] = useState<number>(0);

  const mediaFiles = section.files;

  const playNext = useCallback(() => {
    if (mediaFiles.length <= 1) {
      setCurrentIndex((prev) => (prev === 0 ? -1 : 0));
      setTimeout(() => setCurrentIndex(0), 100);
      return;
    }
    setCurrentIndex((prev) => (prev + 1) % mediaFiles.length);
  }, [mediaFiles]);

  const handleMediaError = useCallback(() => {
    console.warn(`[Section ${section.id}] Error loading file: ${mediaFiles[currentIndex]?.name}`);
    setErrorCount((prev) => prev + 1);

    // A decoder/buffer error must never be treated as a completed video. Keep
    // the current item selected and retry it; only a real native end event is
    // allowed to advance a video to the next file.
    setTimeout(() => {
      setReloadToken((prev) => prev + 1);
    }, 2500);
  }, [section.id, mediaFiles, currentIndex]);

  const handleVideoEnd = useCallback(() => {
    const itemIndex = currentIndex === -1 ? 0 : currentIndex;
    // Native players can emit events while their old surface is being
    // released. Ignore anything that is not the currently selected video.
    if (mediaFiles[itemIndex]?.type === 'video') {
      setErrorCount(0);
      playNext();
    }
  }, [currentIndex, mediaFiles, playNext]);

  // Image slide duration timer
  useEffect(() => {
    if (mediaFiles.length === 0 || configOpen) return;

    const itemIndex = currentIndex === -1 ? 0 : currentIndex;
    const currentMedia = mediaFiles[itemIndex];
    if (currentMedia && currentMedia.type === 'image') {
      const timer = setTimeout(() => {
        playNext();
      }, config.slideDuration);

      return () => clearTimeout(timer);
    }
  }, [currentIndex, mediaFiles, config.slideDuration, configOpen, playNext]);

  const itemIndex = currentIndex === -1 ? 0 : currentIndex;
  const currentMedia = mediaFiles[itemIndex];

  if (!currentMedia || mediaFiles.length === 0) {
    return (
      <View style={styles.emptySectionContainer}>
        <Text style={styles.emptySectionText}>No media in {section.title}</Text>
      </View>
    );
  }

  const sectionKey = `section-${sectionIndex}-${currentMedia.path}-${reloadToken}`;

  return (
    <MediaTransitionView transition={config.mediaTransition || 'fade'} changeKey={sectionKey}>
    <Pressable
      focusable={!configOpen}
      hasTVPreferredFocus={!configOpen && sectionIndex === 0}
      style={styles.mediaWrapper}
      onPress={() => {
        if (!configOpen && config.kioskMode === false) {
          onOpenConfig();
        }
      }}
    >
      {currentMedia.type === 'video' ? (
        sectionIndex === 0 ? (
          // Master Section 1: Native ExoPlayer (Hardware VPU Player with Audio)
          <NativeExoPlayer
            key={`exo_${sectionKey}`}
            srcPath={currentMedia.path}
            muted={false}
            isSecondary={false}
            repeat={mediaFiles.length === 1}
            paused={configOpen}
            resizeMode={config.resizeMode || 'stretch'}
            onVideoEnd={handleVideoEnd}
            onVideoError={handleMediaError}
            style={styles.fullscreenMedia}
          />
        ) : (
          // Secondary Sections 2, 3, 4: Android Native System C++ MediaPlayer (Guaranteed Local File Support & Zero Conflict)
          <NativeAndroidMediaPlayer
            key={`native_media_${sectionKey}`}
            srcPath={currentMedia.path}
            muted={true}
            repeat={mediaFiles.length === 1}
            paused={configOpen}
            resizeMode={config.resizeMode || 'stretch'}
            onVideoEnd={handleVideoEnd}
            onVideoError={handleMediaError}
            style={styles.fullscreenMedia}
          />
        )
      ) : (
        <Image
          source={{ uri: `file://${currentMedia.path}` }}
          style={styles.fullscreenMedia}
          resizeMode={config.resizeMode || 'stretch'}
          onError={handleMediaError}
          fadeDuration={0}
        />
      )}
    </Pressable>
    </MediaTransitionView>
  );
};

const IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.tiff', '.tif', '.svg', '.ico', '.heic', '.heif', '.jp2', '.j2k', '.wbmp', '.jfif', '.pjp', '.pjpeg', '.avif'];
const VIDEO_EXTENSIONS = ['.mp4', '.mov', '.mkv', '.avi', '.3gp', '.webm', '.flv', '.wmv', '.m4v', '.mpg', '.mpeg', '.ts', '.mts', '.m2ts', '.ogv', '.divx', '.xvid', '.asf', '.rm', '.rmvb', '.vob', '.f4v'];
const DURATION_OPTIONS = [
  { label: '5 Sec', value: 5000 },
  { label: '10 Sec', value: 10000 },
  { label: '15 Sec', value: 15000 },
  { label: '20 Sec', value: 20000 },
];

const COLOR_OPTIONS = [
  { label: 'White', value: '#FFFFFF' },
  { label: 'Black', value: '#000000' },
  { label: 'Red', value: '#FF0000' },
  { label: 'Green', value: '#00FF00' },
  { label: 'Blue', value: '#0000FF' },
  { label: 'Yellow', value: '#FFFF00' },
  { label: 'Cyan', value: '#00FFFF' },
  { label: 'Magenta', value: '#FF00FF' },
];

const BG_COLOR_OPTIONS = [
  { label: 'Black', value: '#000000' },
  { label: 'White', value: '#FFFFFF' },
  { label: 'Red', value: '#FF0000' },
  { label: 'Green', value: '#00FF00' },
  { label: 'Blue', value: '#0000FF' },
  { label: 'Yellow', value: '#FFFF00' },
  { label: 'Gray', value: '#808080' },
  { label: 'Transparent', value: 'transparent' },
];

const TICKER_POSITIONS = [
  { label: 'Top', value: 'top' },
  { label: 'Bottom', value: 'bottom' },
];

const FONT_SIZE_OPTIONS = [
  { label: 'Small', value: 24 },
  { label: 'Medium', value: 32 },
  { label: 'Large', value: 40 },
  { label: 'X-Large', value: 50 },
  { label: 'XX-Large', value: 60 },
];

const ORIENTATION_OPTIONS = [
  { label: 'Horizontal (Landscape)', value: 'horizontal' },
  { label: 'Reverse Horizontal', value: 'reverse-horizontal' },
  { label: 'Vertical (Portrait)', value: 'vertical' },
  { label: 'Reverse Vertical', value: 'reverse-vertical' },
];

// Config interface
interface AppConfig {
  slideDuration: number;
  tickerText: string;
  tickerTextColor: string;
  tickerBgColor: string;
  tickerPosition: 'top' | 'middle' | 'bottom';
  tickerFontSize: number;
  tickerFontFamily?: string;
  tickerSpeed?: number;
  tickerDirection?: 'left' | 'right';
  mediaTransition?: MediaTransition;
  usePendrive: boolean;
  resizeMode: 'contain' | 'cover' | 'stretch';
  kioskMode?: boolean;
  orientation?: 'horizontal' | 'reverse-horizontal' | 'vertical' | 'reverse-vertical';
  layoutMode?: 'auto' | 'stack_vertical' | 'stack_horizontal' | 'top2_bottom1' | 'top1_bottom2' | 'grid_2x2';
  sectionRatio?: '50_50' | '60_40' | '70_30' | '40_60' | '30_70';
  showQrCode?: boolean;
  weatherCity?: string;
  weatherUnit?: string;
  rssFeedUrl?: string;
  liveStreamUrl?: string;
  clockFormat?: string;
  prayerCity?: string;
}

interface CustomZone {
  id: string;
  name: string;
  type: 'media' | 'stream' | 'weather' | 'clock' | 'rss' | 'logo';
  left: number;
  top: number;
  width: number;
  height: number;
  zIndex: number;
}

interface CustomLayout {
  enabled: boolean;
  zones: CustomZone[];
}

interface EmergencyAlertData {
  active: boolean;
  type: string;
  title: string;
  message: string;
  sound: boolean;
}

const DEFAULT_CONFIG: AppConfig = {
  slideDuration: 5000,
  tickerText: 'Thank You for Choosing NextView • Your Trusted Digital Signage Partner • Smart Displays. Professional Solutions. Reliable Support. • +91 92278 96944',
  tickerTextColor: '#FFFFFF',
  tickerBgColor: '#000000',
  tickerPosition: 'bottom',
  tickerFontSize: 16,
  tickerFontFamily: 'sans-serif',
  tickerSpeed: 5,
  tickerDirection: 'left',
  mediaTransition: 'fade',
  usePendrive: false,
  resizeMode: 'stretch',
  kioskMode: true,
  orientation: 'horizontal',
  layoutMode: 'auto',
  sectionRatio: '50_50',
  showQrCode: true,
  weatherCity: 'Delhi',
  weatherUnit: 'celsius',
  clockFormat: '12h',
  prayerCity: 'Delhi',
};

const ClockWidget = ({ config }: { config: AppConfig }) => {
  const [timeStr, setTimeStr] = useState<string>('');
  const [dateStr, setDateStr] = useState<string>('');

  useEffect(() => {
    const updateTime = () => {
      const now = new Date();
      const is24h = config.clockFormat === '24h';
      setTimeStr(now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: !is24h }));
      setDateStr(now.toLocaleDateString([], { weekday: 'long', month: 'short', day: 'numeric', year: 'numeric' }));
    };
    updateTime();
    const timer = setInterval(updateTime, 1000);
    return () => clearInterval(timer);
  }, [config.clockFormat]);

  return (
    <View style={{ flex: 1, backgroundColor: 'rgba(15, 23, 42, 0.92)', padding: 14, justifyContent: 'center', alignItems: 'center', borderRadius: 10, borderWidth: 1, borderColor: '#38bdf8' }}>
      <Text style={{ color: '#38bdf8', fontSize: 26, fontWeight: '900', letterSpacing: 1 }}>🕒 {timeStr}</Text>
      <Text style={{ color: '#cbd5e1', fontSize: 12, fontWeight: '600', marginTop: 4 }}>{dateStr}</Text>
    </View>
  );
};

const WeatherWidget = ({ config }: { config: AppConfig }) => {
  const [temp, setTemp] = useState<string>('--');
  const [condition, setCondition] = useState<string>('Loading...');
  const [loading, setLoading] = useState<boolean>(true);

  useEffect(() => {
    const fetchWeather = async () => {
      try {
        const city = config.weatherCity || 'Delhi';
        setLoading(true);
        
        // First, get coordinates from city name using Open-Meteo Geocoding API
        const geoRes = await fetch(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(city)}&count=1&language=en&format=json`);
        const geoData = await geoRes.json();
        
        if (!geoData.results || geoData.results.length === 0) {
          setCondition('City not found');
          setLoading(false);
          return;
        }
        
        const { latitude, longitude, name, country } = geoData.results[0];
        
        // Then fetch weather using the coordinates
        const weatherRes = await fetch(`https://api.open-meteo.com/v1/forecast?latitude=${latitude}&longitude=${longitude}&current_weather=true`);
        const weatherData = await weatherRes.json();
        
        if (weatherData && weatherData.current_weather) {
          const t = Math.round(weatherData.current_weather.temperature);
          const unit = config.weatherUnit === 'fahrenheit' ? '°F' : '°C';
          const tempInUnit = config.weatherUnit === 'fahrenheit' ? Math.round(t * 9/5 + 32) : t;
          setTemp(`${tempInUnit}${unit}`);
          
          const code = weatherData.current_weather.weathercode;
          if (code === 0) setCondition('Clear ☀️');
          else if (code >= 1 && code <= 3) setCondition('Partly Cloudy ⛅');
          else if (code >= 45 && code <= 48) setCondition('Foggy 🌫️');
          else if (code >= 51 && code <= 67) setCondition('Rain 🌧️');
          else if (code >= 71 && code <= 77) setCondition('Snow 🌨️');
          else if (code >= 80 && code <= 82) setCondition('Showers 🌦️');
          else if (code >= 95) setCondition('Thunderstorm ⛈️');
          else setCondition('Cloudy ☁️');
        } else {
          setCondition('Weather unavailable');
        }
      } catch (e) {
        console.error('Weather fetch error:', e);
        setCondition('Error loading weather');
      } finally {
        setLoading(false);
      }
    };
    fetchWeather();
    const interval = setInterval(fetchWeather, 600000); // Refresh every 10 minutes
    return () => clearInterval(interval);
  }, [config.weatherCity, config.weatherUnit]);

  return (
    <View style={{ flex: 1, backgroundColor: 'rgba(15, 23, 42, 0.92)', padding: 14, justifyContent: 'center', alignItems: 'center', borderRadius: 10, borderWidth: 1, borderColor: '#4ade80' }}>
      <Text style={{ color: '#4ade80', fontSize: 24, fontWeight: 'bold' }}>{loading ? 'Loading...' : condition}</Text>
      <Text style={{ color: '#ffffff', fontSize: 20, fontWeight: '800', marginTop: 2 }}>{config.weatherCity || 'Delhi'}: {temp}</Text>
    </View>
  );
};

const DESKTOP_USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36';

const getYouTubeVideoId = (url: string): string => {
  if (url.includes('youtu.be/')) {
    return url.split('youtu.be/')[1]?.split('?')[0]?.split('&')[0] || '';
  } else if (url.includes('youtube.com/watch')) {
    const searchParams = url.split('?')[1] || '';
    const params = new URLSearchParams(searchParams);
    return params.get('v') || '';
  } else if (url.includes('youtube.com/embed/')) {
    return url.split('youtube.com/embed/')[1]?.split('?')[0] || '';
  } else if (url.includes('youtube.com/live/')) {
    return url.split('youtube.com/live/')[1]?.split('?')[0] || '';
  }
  return '';
};

const getYouTubeHtml = (videoId: string): string => {
  return `
<!DOCTYPE html>
<html>
  <head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
      * { margin: 0; padding: 0; overflow: hidden; background: #000; }
      html, body, #player { width: 100%; height: 100%; position: absolute; top: 0; left: 0; }
    </style>
  </head>
  <body>
    <div id="player"></div>
    <script>
      var tag = document.createElement('script');
      tag.src = "https://www.youtube.com/iframe_api";
      var firstScriptTag = document.getElementsByTagName('script')[0];
      firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);
      var player;
      function onYouTubeIframeAPIReady() {
        player = new YT.Player('player', {
          videoId: '${videoId}',
          playerVars: {
            'autoplay': 1,
            'controls': 1,
            'rel': 0,
            'showinfo': 0,
            'loop': 1,
            'playlist': '${videoId}',
            'playsinline': 1,
            'enablejsapi': 1,
            'origin': 'https://www.youtube.com'
          },
          events: {
            'onReady': function(e) {
              e.target.playVideo();
            }
          }
        });
      }
    </script>
  </body>
</html>
  `;
};

const StreamPlayer = ({ streamUrl, resizeMode }: { streamUrl: string; resizeMode: any }) => {
  if (!streamUrl) {
    return (
      <View style={{ flex: 1, backgroundColor: '#0f172a', justifyContent: 'center', alignItems: 'center', padding: 12 }}>
        <Text style={{ color: '#38bdf8', fontSize: 16, fontWeight: 'bold' }}>📺 YouTube / Website / Live Stream Feed</Text>
        <Text style={{ color: '#94a3b8', fontSize: 12, marginTop: 4, textAlign: 'center' }}>Enter YouTube Video, Website, or Video Stream URL in Web CMS</Text>
      </View>
    );
  }

  const isYouTube = streamUrl.includes('youtube.com') || streamUrl.includes('youtu.be');
  const isVideoFile = streamUrl.endsWith('.m3u8') || streamUrl.endsWith('.mp4') || streamUrl.endsWith('.ts') || streamUrl.endsWith('.mkv');

  if (isYouTube) {
    const videoId = getYouTubeVideoId(streamUrl);
    if (videoId) {
      const htmlContent = getYouTubeHtml(videoId);
      return (
        <WebView
          source={{ html: htmlContent, baseUrl: 'https://www.youtube.com' }}
          style={{ flex: 1, width: '100%', height: '100%', backgroundColor: '#000000' }}
          userAgent={DESKTOP_USER_AGENT}
          javaScriptEnabled={true}
          domStorageEnabled={true}
          allowsInlineMediaPlayback={true}
          mediaPlaybackRequiresUserAction={false}
          originWhitelist={['*']}
          mixedContentMode="always"
          androidLayerType="hardware"
          scrollEnabled={false}
        />
      );
    }
  }

  if (!isVideoFile && (streamUrl.startsWith('http://') || streamUrl.startsWith('https://'))) {
    return (
      <WebView
        source={{ uri: streamUrl }}
        style={{ flex: 1, width: '100%', height: '100%' }}
        userAgent={DESKTOP_USER_AGENT}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        allowsInlineMediaPlayback={true}
        originWhitelist={['*']}
        mixedContentMode="always"
        thirdPartyCookiesEnabled={true}
        scrollEnabled={true}
      />
    );
  }

  return (
    <Video
      source={{ uri: streamUrl }}
      style={{ width: '100%', height: '100%' }}
      resizeMode={resizeMode || 'cover'}
      repeat
      controls={false}
      muted={false}
    />
  );
};

const EmergencyAlertOverlay = ({ alert, onClear }: { alert: EmergencyAlertData; onClear: () => void }) => {
  const [flash, setFlash] = useState<boolean>(false);

  useEffect(() => {
    if (!alert.active) return;
    if (alert.sound && (RNNativeModules as any)?.CmsServerModule?.playSirenAlarm) {
      (RNNativeModules as any).CmsServerModule.playSirenAlarm();
    }
    const interval = setInterval(() => setFlash((prev) => !prev), 500);
    return () => {
      clearInterval(interval);
      if ((RNNativeModules as any)?.CmsServerModule?.stopSirenAlarm) {
        (RNNativeModules as any).CmsServerModule.stopSirenAlarm();
      }
    };
  }, [alert.active, alert.sound]);

  if (!alert.active) return null;

  const bg = alert.type === 'FIRE' ? (flash ? '#ef4444' : '#991b1b') : alert.type === 'EVACUATION' ? (flash ? '#f97316' : '#c2410c') : '#0284c7';

  return (
    <View style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: bg, zIndex: 99999, justifyContent: 'center', alignItems: 'center', padding: 24 }}>
      <Text style={{ color: '#ffffff', fontSize: 48, fontWeight: '900', textAlign: 'center', textTransform: 'uppercase', letterSpacing: 2 }}>
        {alert.title || '🚨 EMERGENCY ALERT'}
      </Text>
      <Text style={{ color: '#ffffff', fontSize: 26, fontWeight: 'bold', textAlign: 'center', marginTop: 24, lineHeight: 36 }}>
        {alert.message || 'PLEASE EVACUATE IMMEDIATELY!'}
      </Text>
      <Pressable
        onPress={() => {
          if ((RNNativeModules as any)?.CmsServerModule?.stopSirenAlarm) {
            (RNNativeModules as any).CmsServerModule.stopSirenAlarm();
          }
          onClear();
        }}
        style={{ marginTop: 40, backgroundColor: '#ffffff', paddingHorizontal: 28, paddingVertical: 14, borderRadius: 8 }}
      >
        <Text style={{ color: '#000000', fontSize: 16, fontWeight: 'bold' }}>CLEAR EMERGENCY ALERT</Text>
      </Pressable>
    </View>
  );
};

/**
 * A measured, two-copy marquee.  It never animates a synthetic 50,000px text
 * node: once the first copy has travelled its exact measured width, the second
 * copy occupies precisely the same screen position and the loop can restart
 * without a visible jump or a pause.
 */
const ContinuousTicker = React.memo(({
  text,
  color,
  backgroundColor,
  fontSize,
  fontFamily,
  speed = 5,
  direction = 'left',
  paused,
  overlay = false,
}: {
  text: string;
  color: string;
  backgroundColor: string;
  fontSize: number;
  fontFamily?: string;
  speed?: number;
  direction?: 'left' | 'right';
  paused: boolean;
  overlay?: boolean;
}) => {
  const { width: viewportWidth } = useWindowDimensions();
  const translateX = useRef(new Animated.Value(0)).current;
  const [measuredWidth, setMeasuredWidth] = useState(0);
  const lastMeasuredWidth = useRef(0);
  const tickerText = String(text || '');
  const measurementText = `${tickerText}  •  `;
  const fallbackWidth = Math.ceil(
    Math.max(1, Array.from(measurementText).length) * Math.max(12, fontSize) * 0.7 + 32,
  );
  const animationWidth = Math.ceil(measuredWidth) || fallbackWidth;
  const copyCount = Math.max(2, Math.ceil(viewportWidth / animationWidth) + 2);

  useEffect(() => {
    lastMeasuredWidth.current = 0;
    setMeasuredWidth(0);
  }, [fontSize, tickerText]);

  useEffect(() => {
    translateX.stopAnimation();
    if (paused || !tickerText || viewportWidth <= 0) return;

    // Both directions hand off between two adjacent copies at x=0. Starting
    // RTL at the viewport edge made Animated.loop reset through empty space.
    translateX.setValue(direction === 'right' ? -animationWidth : 0);
    const animation = Animated.loop(
      Animated.timing(translateX, {
        toValue: direction === 'right' ? 0 : -animationWidth,
        duration: Math.max(900, Math.round((animationWidth / (25 + Math.max(1, Math.min(10, speed)) * 12)) * 1000)),
        easing: Easing.linear,
        useNativeDriver: true,
      }),
      { resetBeforeIteration: true },
    );
    animation.start();

    return () => animation.stop();
  }, [animationWidth, direction, paused, speed, tickerText, translateX, viewportWidth]);

  return (
    <View
      renderToHardwareTextureAndroid
      style={[
        styles.tickerBar,
        overlay && styles.tickerBarOverlay,
        {
          height: fontSize + 16,
          backgroundColor,
          ...(overlay ? { marginTop: -(fontSize + 16) / 2 } : {}),
        },
      ]}
    >
      <Animated.View
        style={[
          styles.tickerTrack,
          { left: 0, transform: [{ translateX }] },
        ]}
      >
        <Text
          numberOfLines={1}
          onTextLayout={(event) => {
            const lines = event.nativeEvent.lines || [];
            const nextWidth = Math.ceil(lines[0]?.width || 0);
            if (nextWidth > 0 && Math.abs(nextWidth - lastMeasuredWidth.current) >= 1) {
              lastMeasuredWidth.current = nextWidth;
              setMeasuredWidth(nextWidth);
            }
          }}
          style={[styles.tickerMeasureText, { fontSize, fontFamily: fontFamily || 'sans-serif' }]}
        >
          {measurementText}
        </Text>
        {Array.from({ length: copyCount }, (_, copy) => (
          <Text
            key={copy}
            numberOfLines={1}
            ellipsizeMode="clip"
            style={[
              styles.tickerText,
              { width: animationWidth, color, fontSize, fontFamily: fontFamily || 'sans-serif' },
            ]}
          >
            {measurementText}
          </Text>
        ))}
      </Animated.View>
    </View>
  );
});

const getRatioFlex = (ratio?: string, isFirst?: boolean) => {
  switch (ratio) {
    case '60_40':
      return isFirst ? 0.6 : 0.4;
    case '70_30':
      return isFirst ? 0.7 : 0.3;
    case '40_60':
      return isFirst ? 0.4 : 0.6;
    case '30_70':
      return isFirst ? 0.3 : 0.7;
    case '50_50':
    default:
      return 1;
  }
};

export default function App() {
  const [allSections, setSections] = useState<SectionData[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const playerWindowDimensions = useWindowDimensions();
  const scanInFlightRef = useRef(false);
  const scanFolderRef = useRef<(() => Promise<void>) | null>(null);
  const [pdfProgress, setPdfProgress] = useState({ active: false, percent: 0, file: '', currentPage: 0, totalPages: 0 });
  const pdfProgressVisibleRef = useRef(false);
  const [settingsOpen, setSettingsOpen] = useState<boolean>(false);
  const [configOpen, setConfigOpen] = useState<boolean>(false);
  const [config, setConfig] = useState<AppConfig>(DEFAULT_CONFIG);
  const [tempConfig, setTempConfig] = useState<AppConfig>(DEFAULT_CONFIG);
  const [resizeMode, setResizeMode] = useState<'contain' | 'cover' | 'stretch'>('stretch');
  const [focusedIndex, setFocusedIndex] = useState<number>(0);
  const [focusedId, setFocusedId] = useState<string>('');
  const [showUsbPopup, setShowUsbPopup] = useState<boolean>(false);
  const licenseInputRef = useRef<any>(null);
  const [customTextColor, setCustomTextColor] = useState<string>(config.tickerTextColor);
  const [customBgColor, setCustomBgColor] = useState<string>(config.tickerBgColor);
  const [customLayout, setCustomLayout] = useState<CustomLayout>({ enabled: false, zones: [] });
  const [emergencyAlert, setEmergencyAlert] = useState<EmergencyAlertData>({
    active: false,
    type: '',
    title: '',
    message: '',
    sound: false,
  });
  const sections = allSections;

  // License/Activation states
  const [licenseDeviceId, setLicenseDeviceId] = useState<string>('');
  const [licenseInput, setLicenseInput] = useState<string>('');
  const [licensed, setLicensed] = useState<boolean>(false);
  const [licenseBusy, setLicenseBusy] = useState<boolean>(false);
  const [licenseStatus, setLicenseStatus] = useState<string>('');
  const [licenseReady, setLicenseReady] = useState<boolean>(false);
  const [licenseInputFocused, setLicenseInputFocused] = useState<boolean>(false);
  const [licenseButtonFocused, setLicenseButtonFocused] = useState<boolean>(false);
  const [ready, setReady] = useState<boolean>(false);

  // Initialize license check
  useEffect(() => {
    let mounted = true;
    const initLicense = async () => {
      try {
        console.log('Initializing license check...');
        const deviceId = (NativeModules as any)?.DeviceIdModule?.getDeviceId?.();
        console.log('Device ID from native module:', deviceId);

        if (!deviceId) {
          console.log('Device ID not found, skipping license check');
          if (mounted) {
            setLicenseStatus('Unable to read device id.');
            setLicenseReady(true);
            setLicensed(false);
          }
          return;
        }

        setLicenseDeviceId(deviceId);

        const storedLicense = await readStoredLicense();
        console.log('Stored license:', storedLicense);
        console.log('License key length:', storedLicense.licenseKey?.length);
        console.log('License key valid:', !!storedLicense.licenseKey && storedLicense.licenseKey.length >= 8);

        if (storedLicense.licenseKey) {
          setLicenseInput(storedLicense.licenseKey);
        }

        const active = await hasLocalActivationForDevice(deviceId);
        console.log('Local activation check result:', active);
        console.log('Licensed state will be set to:', !!active);

        if (mounted) {
          setLicensed(!!active);
          if (active) {
            setReady(true); // Auto-set ready if already activated
            setLicenseStatus('Device activated. Starting player...');
          } else {
            setLicenseStatus('License required. Enter key to activate.');
          }
        }
      } catch (e: any) {
        console.error('License check error:', e);
        if (mounted) {
          setLicensed(false);
          setLicenseStatus('Unable to read device id.');
        }
      } finally {
        if (mounted) setLicenseReady(true);
      }
    };
    initLicense();
    return () => {
      mounted = false;
    };
  }, []);

  const onActivateLicense = async () => {
    if (licenseBusy) return;
    setLicenseBusy(true);
    try {
      console.log('Starting activation with deviceId:', licenseDeviceId, 'key:', licenseInput);
      const result = await activateDeviceWithKey(licenseDeviceId, licenseInput);
      console.log('Activation result:', result);
      setLicenseStatus(result.message);
      if (result.success) {
        setLicenseInput(String(licenseInput || '').trim().toUpperCase());
        setLicensed(true);
        setReady(true); // Set ready to true directly to proceed to main screen

        // Enable kiosk mode after successful activation
        if (isKioskModuleAvailable()) {
          console.log('Enabling kiosk mode after activation');
          enableKioskMode();
        }

      }
    } catch (e: any) {
      console.error('License activation error:', e);
      setLicenseStatus('Activation failed: ' + (e?.message || 'Unknown error'));
    } finally {
      setLicenseBusy(false);
    }
  };

  // Auto-set ready when licensed
  useEffect(() => {
    if (licensed && !ready) {
      setReady(true);
    }
  }, [licensed, ready]);

  // Auto-focus license input on TV when license screen is shown
  useEffect(() => {
    if (licenseReady && !licensed && licenseInputRef.current) {
      setTimeout(() => {
        licenseInputRef.current?.focus();
      }, 500);
    }
  }, [licenseReady, licensed]);

  
  // Permissions States
  const [hasStorage, setHasStorage] = useState<boolean>(false);
  const [permissionsLoaded, setPermissionsLoaded] = useState<boolean>(false);

  // Request overlay permission
  // Active resolved ads directory path (internal or USB)
  const [activeAdsDir, setActiveAdsDir] = useState<string>(ADS_DIR);
  const [cmsInfo, setCmsInfo] = useState<{ url: string; qrCode: string }>({ url: '', qrCode: '' });

  // Start Embedded HTTP CMS Web Server on Port 9090
  useEffect(() => {
    if ((RNNativeModules as any)?.CmsServerModule?.startServer) {
      (RNNativeModules as any).CmsServerModule.startServer()
        .then((res: { url: string; qrCode: string }) => {
          console.log('Embedded CMS Web Server running at:', res?.url);
          if (res) {
            setCmsInfo(res);
          }
        })
        .catch((err: any) => console.warn('Failed to start CMS Server:', err));
    }
  }, []);

  // Refresh QR code and IP address when app comes to foreground (TV on/auto-open)
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (nextAppState) => {
      if (nextAppState === 'active' && (RNNativeModules as any)?.CmsServerModule?.getIpAddress) {
        console.log('App came to foreground, refreshing QR/IP');
        (RNNativeModules as any).CmsServerModule.getIpAddress()
          .then((res: { url: string; qrCode: string }) => {
            if (res) {
              setCmsInfo(res);
              console.log('QR/IP refreshed:', res.url);
            }
          })
          .catch((err: any) => console.warn('Failed to refresh QR/IP:', err));
      }
    });

    return () => {
      subscription?.remove();
    };
  }, []);

  // Load the durable CMS config first. AsyncStorage remains a local fallback.
  const loadConfig = useCallback(async () => {
    try {
      const cmsModule = (RNNativeModules as any)?.CmsServerModule;
      const savedConfig = await cmsModule?.getSavedConfig?.() || await AsyncStorage.getItem('tvads_config');
      if (savedConfig) {
        const parsed = typeof savedConfig === 'string' ? JSON.parse(savedConfig) : savedConfig;
        const merged = { ...DEFAULT_CONFIG, ...parsed };
        setConfig(merged);
        setTempConfig(merged);
        setResizeMode(merged.resizeMode || 'stretch');
        setCustomTextColor(merged.tickerTextColor || '#FFFFFF');
        setCustomBgColor(merged.tickerBgColor || '#000000');
        if (merged.orientation) {
          (RNNativeModules as any)?.DeviceIdModule?.applyOrientation?.(merged.orientation);
        }
      }

      const savedLayout = await cmsModule?.getSavedLayout?.();
      if (savedLayout) {
        const layout = typeof savedLayout === 'string' ? JSON.parse(savedLayout) : savedLayout;
        if (layout && Array.isArray(layout.zones)) setCustomLayout(layout);
      }
    } catch (e) {
      console.warn('Failed to load config:', e);
    }
  }, []);

  // Save config to AsyncStorage
  const saveConfig = useCallback(async (newConfig: AppConfig) => {
    try {
      await AsyncStorage.setItem('tvads_config', JSON.stringify(newConfig));
      setConfig(newConfig);
      setTempConfig(newConfig);
      setResizeMode(newConfig.resizeMode);
      setCustomTextColor(newConfig.tickerTextColor);
      setCustomBgColor(newConfig.tickerBgColor);
      if (newConfig.orientation) {
        (RNNativeModules as any)?.DeviceIdModule?.applyOrientation?.(newConfig.orientation);
      }
      console.log('Config saved:', newConfig);
    } catch (e) {
      console.warn('Failed to save config:', e);
    }
  }, []);

  // USB Storage Ads folder auto-detection using native UsbManagerModule
  const resolveAdsDirectory = useCallback(async () => {
    console.log('=== resolveAdsDirectory START ===');
    console.log('UsbModule available:', isUsbModuleAvailable());

    if (config.usePendrive && isUsbModuleAvailable()) {
      try {
        const usbState = await getCurrentUsbState();
        console.log('USB State received:', JSON.stringify(usbState));
        console.log('USB mounted:', usbState.mounted);
        console.log('USB mountPath:', usbState.mountPath);

        if (usbState.mounted && usbState.mountPath) {
          const usbAdsPath = `${usbState.mountPath}/nvsign`;
          const usbFolderExists = await RNFS.exists(usbAdsPath);
          console.log(`USB nvsign folder exists: ${usbFolderExists}`);
          if (usbFolderExists) {
            console.log(`✓✓✓ SUCCESS: Using USB nvsign folder at ${usbAdsPath}`);
            return usbAdsPath;
          }
        }

        console.log('USB not mounted or nvsign folder not found on USB, falling back to internal storage');
      } catch (e) {
        console.error('Error getting USB state:', e);
      }
    }

    // Default to internal storage nvsign folder
    console.log('>>> INTERNAL STORAGE MODE: Using internal storage nvsign folder');
    const internalDir = `${RNFS.ExternalStorageDirectoryPath}/nvsign`;
    const internalExists = await RNFS.exists(internalDir);
    console.log(`nvsign folder exists: ${internalExists}`);

    if (!internalExists) {
      console.log('Creating nvsign folder...');
      await RNFS.mkdir(internalDir);
    }

    console.log('=== resolveAdsDirectory END ===');
    console.log('Returning internal path:', internalDir);
    return internalDir;
  }, [config.usePendrive]);

  // Request storage permission
  const requestPermission = useCallback(async () => {
    if (!PermissionModule) return;
    try {
      await PermissionModule.requestStoragePermission();
      // Recheck permission after request
      const granted = await PermissionModule.checkStoragePermission();
      setHasStorage(granted);
    } catch (e) {
      console.warn('Failed to request storage permission:', e);
    }
  }, []);

  // Check storage permission
  const checkStoragePermission = useCallback(async () => {
    if (!PermissionModule) {
      setHasStorage(true);
      setPermissionsLoaded(true);
      return;
    }
    try {
      const storageGranted = await PermissionModule.checkStoragePermission();
      setHasStorage(storageGranted);

    } catch (e) {
      console.warn('Storage permission query failed:', e);
    } finally {
      setPermissionsLoaded(true);
    }
  }, []);

  // Check permissions on mount and when app returns from settings screen
  useEffect(() => {
    checkStoragePermission();
    loadConfig();

    const subscription = AppState.addEventListener('change', (nextAppState) => {
      if (nextAppState === 'active') {
        // Only check permission if popup is not already showing
        checkStoragePermission();
        loadConfig();
      }
    });

    return () => {
      subscription.remove();
    };
  }, [checkStoragePermission, loadConfig]);

  // Scan and load section media folders with memory optimization
  const scanFolder = useCallback(async () => {
    // USB/media watchers run in the background. Never overlap scans and never
    // put an already-playing screen back into a loading state: that unmounts
    // native video surfaces and restarts an in-progress video.
    if (scanInFlightRef.current) return;
    scanInFlightRef.current = true;
    console.log('=== scanFolder START ===');
    if (allSections.length === 0) setLoading(true);
    try {
      console.log('Scanning folder with usePendrive:', config.usePendrive);
      const resolvedDir = await resolveAdsDirectory();
      setActiveAdsDir(resolvedDir);
      console.log('Resolved directory:', resolvedDir);

      // Check if directory exists before reading
      const dirExists = await RNFS.exists(resolvedDir);
      console.log('Directory exists:', dirExists);

      if (!dirExists) {
        console.log('Directory does not exist, creating it:', resolvedDir);
        await RNFS.mkdir(resolvedDir);
      }

      // Native, file-based conversion: this never reads PDFs into the JS heap. It completes
      // before the normal media scan, so the existing player receives only finished JPG pages.
      const pdfConverter = (RNNativeModules as any)?.PdfConversionModule;
      if (pdfConverter?.convertSectionPdfs) {
        const converted = await pdfConverter.convertSectionPdfs(resolvedDir);
        if (converted > 0) console.log(`Converted ${converted} PDF(s) to image pages`);
      }

      console.log('Reading directory contents...');
      const entries = await RNFS.readDir(resolvedDir);
      console.log('Total entries found:', entries.length);

      // Find subdirectories matching section1, section2, section3, etc.
      const sectionDirs = entries.filter((e) => e.isDirectory() && /^section/i.test(e.name));

      // Sort section directories numerically: section1, section2, section3, etc.
      sectionDirs.sort((a, b) => {
        const numA = parseInt(a.name.replace(/\D/g, ''), 10) || 0;
        const numB = parseInt(b.name.replace(/\D/g, ''), 10) || 0;
        if (numA !== numB) return numA - numB;
        return a.name.localeCompare(b.name);
      });

      const sectionsList: SectionData[] = [];

      if (sectionDirs.length > 0) {
        console.log(`Found ${sectionDirs.length} section directories in nvsign`);
        for (const sDir of sectionDirs) {
          const sFiles = await RNFS.readDir(sDir.path);
          const list: MediaItem[] = [];
          for (const file of sFiles) {
            if (file.isFile() && file.name !== '_order.json') {
              const ext = '.' + file.name.split('.').pop()?.toLowerCase();
              if (IMAGE_EXTENSIONS.includes(ext)) {
                list.push({ name: file.name, path: file.path, type: 'image' });
              } else if (VIDEO_EXTENSIONS.includes(ext)) {
                list.push({ name: file.name, path: file.path, type: 'video' });
              }
            }
          }

          // Custom file playback order support via _order.json
          let customOrder: string[] = [];
          const orderFilePath = `${sDir.path}/_order.json`;
          if (await RNFS.exists(orderFilePath)) {
            try {
              const orderData = await RNFS.readFile(orderFilePath, 'utf8');
              customOrder = JSON.parse(orderData);
            } catch (_e) {}
          }

          if (customOrder.length > 0) {
            list.sort((a, b) => {
              const idxA = customOrder.indexOf(a.name);
              const idxB = customOrder.indexOf(b.name);
              if (idxA !== -1 && idxB !== -1) return idxA - idxB;
              if (idxA !== -1) return -1;
              if (idxB !== -1) return 1;
              return a.name.localeCompare(b.name);
            });
          } else {
            list.sort((a, b) => a.name.localeCompare(b.name));
          }

          sectionsList.push({
            id: sDir.name,
            title: sDir.name.toUpperCase(),
            files: list,
          });
        }
      } else {
        // Fallback: No section directories found, scan files directly in root nvsign as Section 1
        console.log('No section directories found. Reading root nvsign files as Section 1');
        const list: MediaItem[] = [];
        for (const file of entries) {
          if (file.isFile() && file.name !== '_order.json') {
            const ext = '.' + file.name.split('.').pop()?.toLowerCase();
            if (IMAGE_EXTENSIONS.includes(ext)) {
              list.push({ name: file.name, path: file.path, type: 'image' });
            } else if (VIDEO_EXTENSIONS.includes(ext)) {
              list.push({ name: file.name, path: file.path, type: 'video' });
            }
          }
        }

        let customOrder: string[] = [];
        const orderFilePath = `${resolvedDir}/_order.json`;
        if (await RNFS.exists(orderFilePath)) {
          try {
            const orderData = await RNFS.readFile(orderFilePath, 'utf8');
            customOrder = JSON.parse(orderData);
          } catch (_e) {}
        }

        if (customOrder.length > 0) {
          list.sort((a, b) => {
            const idxA = customOrder.indexOf(a.name);
            const idxB = customOrder.indexOf(b.name);
            if (idxA !== -1 && idxB !== -1) return idxA - idxB;
            if (idxA !== -1) return -1;
            if (idxB !== -1) return 1;
            return a.name.localeCompare(b.name);
          });
        } else {
          list.sort((a, b) => a.name.localeCompare(b.name));
        }

        sectionsList.push({
          id: 'section1',
          title: 'SECTION 1',
          files: list,
        });
      }

      console.log(`Loaded ${sectionsList.length} sections into player state.`);
      // The folder watcher runs periodically. Do not replace an identical
      // playlist: a needless state update can make some TV renderers recreate
      // their video surface while a long video is playing.
      setSections((previousSections) => {
        const samePlaylist = previousSections.length === sectionsList.length &&
          previousSections.every((previous, index) => {
            const next = sectionsList[index];
            return previous.id === next.id &&
              previous.files.length === next.files.length &&
              previous.files.every((file, fileIndex) => {
                const nextFile = next.files[fileIndex];
                return file.path === nextFile?.path && file.type === nextFile?.type;
              });
          });
        return samePlaylist ? previousSections : sectionsList;
      });
    } catch (err) {
      console.warn('Error reading Ads directory:', err);
      setSections([]);
    } finally {
      if (pdfProgressVisibleRef.current) {
        // Keep the completed 100% state on screen briefly before handing off to the player.
        setTimeout(() => {
          pdfProgressVisibleRef.current = false;
          setPdfProgress((current) => ({ ...current, active: false }));
          setLoading(false);
        }, 500);
      } else {
        if (allSections.length === 0) setLoading(false);
      }
      scanInFlightRef.current = false;
    }
  }, [allSections.length, config.usePendrive, resolveAdsDirectory]);

  // USB callbacks outlive a render. Always invoke the latest scanner after a
  // mode change, otherwise an old closure can scan the previous storage path.
  useEffect(() => {
    scanFolderRef.current = scanFolder;
  }, [scanFolder]);

  // Per-page progress comes directly from the native PdfRenderer background worker.
  useEffect(() => {
    const pdfConverter = (RNNativeModules as any)?.PdfConversionModule;
    if (!pdfConverter) return;
    const emitter = new NativeEventEmitter(pdfConverter);
    const listener = emitter.addListener('pdfConversionProgress', (event: any) => {
      pdfProgressVisibleRef.current = true;
      setPdfProgress({
        active: true,
        percent: Math.max(0, Math.min(100, Number(event?.percent) || 0)),
        file: String(event?.file || ''),
        currentPage: Number(event?.currentPage) || 0,
        totalPages: Number(event?.totalPages) || 0,
      });
    });
    return () => listener.remove();
  }, []);

  // Listen for real-time web CMS events (config updates and media uploads)
  useEffect(() => {
    if (!(RNNativeModules as any)?.CmsServerModule) return;
    const eventEmitter = new NativeEventEmitter((RNNativeModules as any).CmsServerModule);
    const listener = eventEmitter.addListener('embeddedCmsEvent', (event: { type: string; payload: string }) => {
      if (event.type === 'config-updated') {
        try {
          const parsed = JSON.parse(event.payload);
          const merged = { ...DEFAULT_CONFIG, ...parsed };
          setConfig(merged);
          setTempConfig(merged);
          setResizeMode(merged.resizeMode || 'stretch');
          setCustomTextColor(merged.tickerTextColor || '#FFFFFF');
          setCustomBgColor(merged.tickerBgColor || '#000000');
          if (merged.orientation) {
            (RNNativeModules as any)?.DeviceIdModule?.applyOrientation?.(merged.orientation);
          }
          AsyncStorage.setItem('tvads_config', JSON.stringify(merged));
          scanFolder();
        } catch (e) {
          console.warn('Error handling config-updated event:', e);
        }
      } else if (event.type === 'media-updated') {
        console.log('Media uploaded or deleted via Web CMS, rescanning folders...');
        scanFolder();
      } else if (event.type === 'emergency-alert') {
        try {
          const payload = typeof event.payload === 'string' ? JSON.parse(event.payload) : event.payload;
          setEmergencyAlert({ active: true, ...payload });
        } catch (_e) {}
      } else if (event.type === 'clear-emergency') {
        setEmergencyAlert({ active: false, type: '', title: '', message: '', sound: false });
      } else if (event.type === 'layout-updated') {
        try {
          const layoutData = typeof event.payload === 'string' ? JSON.parse(event.payload) : event.payload;
          setCustomLayout(layoutData);
        } catch (_e) {}
      }
    });
    return () => {
      listener.remove();
    };
  }, [scanFolder]);

  // Helper for responsive split-screen layouts
  const getSectionStyle = (index: number, count: number) => {
    if (count <= 1) {
      return { flex: 1, width: '100%' as const, height: '100%' as const };
    }
    if (count === 2) {
      return {
        flex: 1,
        height: '100%' as const,
        borderRightWidth: index === 0 ? 2 : 0,
        borderColor: '#3b82f6',
      };
    }
    if (count === 3) {
      return {
        flex: 1,
        height: '100%' as const,
        borderRightWidth: index < 2 ? 2 : 0,
        borderColor: '#3b82f6',
      };
    }
    // 4 or more sections: grid layout (50% width, 50% height)
    return {
      width: '50%' as const,
      height: '50%' as const,
      borderRightWidth: index % 2 === 0 ? 1 : 0,
      borderBottomWidth: index < 2 ? 1 : 0,
      borderColor: '#3b82f6',
    };
  };

  // Check if all sections are empty
  const totalMediaCount = sections.reduce((acc, sec) => acc + sec.files.length, 0);

  // USB is the source of truth: inserting a drive enables USB mode and removing
  // it returns playback to the CMS/internal nvsign folder.
  useEffect(() => {
    if (!isUsbModuleAvailable()) return;

    let disposed = false;
    const syncUsbMode = (usbState: UsbState) => {
      if (disposed) return;
      console.log('USB state changed:', JSON.stringify(usbState));
      const shouldUsePendrive = usbState.mounted;
      setConfig((previous) => {
        if (previous.usePendrive === shouldUsePendrive) return previous;
        const next = { ...previous, usePendrive: shouldUsePendrive };
        AsyncStorage.setItem('tvads_config', JSON.stringify(next))
          .catch((error) => console.warn('Could not persist automatic USB mode:', error));
        return next;
      });
      setTempConfig((previous) => (
        previous.usePendrive === shouldUsePendrive
          ? previous
          : { ...previous, usePendrive: shouldUsePendrive }
      ));

      if (usbState.mounted) {
        console.log('USB detected - scanning USB storage...');
        setShowUsbPopup(true);
        setTimeout(() => {
          setShowUsbPopup(false);
        }, 5000);
      } else {
        console.log('USB removed - switching back to internal storage...');
      }
      // Let the config state and its scanner closure update first.
      setTimeout(() => {
        scanFolderRef.current?.();
      }, 600);
    };

    getCurrentUsbState().then(syncUsbMode).catch((error) => {
      console.warn('Could not read initial USB state:', error);
    });
    const unsubscribe = subscribeUsbState(syncUsbMode);

    return () => {
      disposed = true;
      unsubscribe();
    };
  }, []);

  // Kiosk mode - enable/disable native kiosk mode when config changes
  useEffect(() => {
    if (!isKioskModuleAvailable()) return;

    if (config.kioskMode) {
      console.log('Enabling native kiosk mode');
      enableKioskMode();
    } else {
      console.log('Disabling native kiosk mode');
      disableKioskMode();
    }
  }, [config.kioskMode]);

  // Initial scan triggered only when storage permission is verified or config changes
  useEffect(() => {
    if (permissionsLoaded && hasStorage) {
      console.log('Triggering scan - permissions loaded and storage available');
      scanFolder();
    }
  }, [permissionsLoaded, hasStorage, scanFolder, config.usePendrive]);

  // Pick up PDFs (and ordinary media copied to internal storage or USB) while the app stays open.
  // This also retries a PDF whose prior conversion was interrupted without requiring an APK restart.
  useEffect(() => {
    if (!permissionsLoaded || !hasStorage) return;
    const interval = setInterval(scanFolder, 15000);
    return () => clearInterval(interval);
  }, [permissionsLoaded, hasStorage, scanFolder]);

  // Handle D-pad Menu, D-pad Up or OK to open config settings
  useEffect(() => {
    if (typeof TVEventHandler === 'undefined' || !TVEventHandler) {
      console.warn('TVEventHandler is not defined in this React Native core build. Falling back to focus-based Pressable triggers.');
      return;
    }
    try {
      const tvEventHandler = new TVEventHandler();
      tvEventHandler.enable(null, (_cmp: any, evt: any) => {
        console.log('TV Event:', evt);
        // Handle multiple event types for different TV remotes
        if (evt && (
          evt.eventType === 'select' ||
          evt.eventType === 'playPause' ||
          evt.eventType === 'menu' ||
          evt.eventType === 'longPressSelect' ||
          evt.eventType === 'down'
        )) {
          // OK/Menu/Down button opens config settings ONLY if kioskMode is unlocked
          if (!configOpen && config.kioskMode === false) {
            setConfigOpen(true);
            setTempConfig(config); // Initialize tempConfig with current config
            setFocusedIndex(0);
          }
        }
      });

      return () => {
        tvEventHandler.disable();
      };
    } catch (e) {
      console.warn('Could not initialize TVEventHandler:', e);
    }
  }, [configOpen, config]);

  // Back button closes config menu or blocks exit if locked
  useEffect(() => {
    const backAction = () => {
      console.log('Back pressed - configOpen:', configOpen, 'kioskMode:', config.kioskMode);
      if (configOpen) {
        setConfigOpen(false);
        setTempConfig(config); // Revert to actual config on cancel
        return true; // prevent default back press
      }
      if (config.kioskMode !== false) {
        // Block back press completely when Kiosk mode is locked
        return true;
      }
      return false; // exit app normally if unlocked
    };

    const backHandler = BackHandler.addEventListener(
      'hardwareBackPress',
      backAction
    );

    return () => backHandler.remove();
  }, [configOpen, config]);

  // Show license screen if not licensed
  if (!licensed && licenseReady) {
    console.log('Showing license screen, deviceId:', licenseDeviceId);
    return (
      <View style={styles.darkContainer}>
        <StatusBar hidden />
        <View style={styles.licenseCard}>
          <Text style={styles.connectTitle}>Activate Device</Text>
          <Text style={styles.licenseHint}>Share Device ID and enter license key provided by admin.</Text>

          <View style={styles.licenseRow}>
            <Text style={styles.licenseLabel}>Device ID</Text>
            <Text selectable style={styles.licenseValue}>{licenseDeviceId || 'unknown'}</Text>
          </View>

          <View style={styles.licenseRow}>
            <Text style={styles.licenseLabel}>License Key</Text>
            <TextInput
              ref={licenseInputRef}
              value={licenseInput}
              onChangeText={setLicenseInput}
              onFocus={() => setLicenseInputFocused(true)}
              onBlur={() => setLicenseInputFocused(false)}
              autoCapitalize="characters"
              autoCorrect={false}
              placeholder="Enter key"
              placeholderTextColor="rgba(210,220,232,0.45)"
              style={[
                styles.licenseInput,
                licenseInputFocused ? styles.licenseInputFocused : null,
              ]}
              focusable
              hasTVPreferredFocus
            />
          </View>

          <Pressable
            onPress={onActivateLicense}
            onFocus={() => setLicenseButtonFocused(true)}
            onBlur={() => setLicenseButtonFocused(false)}
            disabled={licenseBusy}
            style={({ pressed }) => [
              styles.licenseBtn,
              licenseButtonFocused ? styles.licenseBtnFocused : null,
              pressed && !licenseBusy ? { opacity: 0.85 } : null,
              licenseBusy ? { opacity: 0.55 } : null,
            ]}
            focusable
          >
            <Text style={styles.licenseBtnText}>
              {licenseBusy ? 'Verifying...' : 'Save And Activate'}
            </Text>
          </Pressable>

          <Text style={styles.licenseStatus}>{licenseStatus}</Text>
        </View>
      </View>
    );
  }

  // Render USB detection popup
  if (showUsbPopup) {
    return (
      <View style={styles.darkContainer}>
        <StatusBar hidden />
        <View style={styles.wizardCard}>
          <Text style={styles.wizardEmoji}>🔌</Text>
          <Text style={styles.wizardTitle}>USB Pendrive Detected</Text>
          <Text style={styles.wizardDesc}>
            Pendrive with media files detected. Auto-enabled pendrive mode.
          </Text>

          <Pressable
            focusable
            hasTVPreferredFocus={true}
            onPress={() => setShowUsbPopup(false)}
            style={({ pressed, focused }: any) => [
              styles.btn,
              focused && styles.btnFocused,
              pressed && styles.btnPressed,
              { marginTop: 12, minWidth: 200 }
            ]}
          >
            <Text style={styles.btnText}>OK</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  if (permissionsLoaded && !hasStorage) {
    return (
      <View style={styles.darkContainer}>
        <StatusBar hidden />
        <View style={styles.wizardCard}>
          <Text style={styles.wizardEmoji}>📁</Text>
          <Text style={styles.wizardTitle}>Allow File Access</Text>
          <Text style={styles.wizardDesc}>
            Allow access once so your signage player can read media from TV storage and USB drives.
          </Text>

          {!hasStorage && (
            <Pressable
              focusable
              hasTVPreferredFocus={true}
              onPress={requestPermission}
              style={({ pressed, focused }: any) => [
                styles.btn,
                focused && styles.btnFocused,
                pressed && styles.btnPressed,
                { marginTop: 12, minWidth: 300 }
              ]}
            >
              <Text style={styles.btnText}>ALLOW MEDIA & FILE ACCESS</Text>
            </Pressable>
          )}

          <Text style={styles.permissionSetupHint}>After allowing file access in Android Settings, press BACK to return here. Setup will continue automatically.</Text>
        </View>
      </View>
    );
  }

  if (loading) {
    return (
      <View style={styles.darkContainer}>
        <StatusBar hidden />
        <ActivityIndicator size="large" color="#6366f1" />
        {pdfProgress.active ? (
          <View style={styles.pdfProgressCard}>
            <Text style={styles.pdfProgressTitle}>{pdfProgress.percent === 100 && !pdfProgress.file ? 'Images ready to play' : 'Preparing PDF for playback'}</Text>
            <Text style={styles.pdfProgressFile}>{pdfProgress.file || (pdfProgress.percent === 100 ? 'Conversion complete' : 'Reading PDF...')}</Text>
            <View style={styles.pdfProgressTrack}><View style={[styles.pdfProgressFill, { width: `${pdfProgress.percent}%` }]} /></View>
            <Text style={styles.pdfProgressText}>{pdfProgress.percent}% {pdfProgress.totalPages > 0 ? `• Page ${pdfProgress.currentPage}/${pdfProgress.totalPages}` : ''}</Text>
          </View>
        ) : <Text style={styles.statusText}>Scanning storage for Ads...</Text>}
      </View>
    );
  }

  // Render instructions screen if folder is empty or not readable
  if (totalMediaCount === 0) {
    return (
      <View style={styles.darkContainer}>
        <StatusBar hidden />
        <View style={styles.instructionCard}>
          <Text style={styles.emojiTitle}>NvAdPlayer</Text>
          <View style={styles.readyPill}><Text style={styles.readyPillText}>PLAYER READY</Text></View>
          <Text style={styles.emptyScreenTitle}>Your display is ready</Text>
          <Text style={styles.emptyScreenSubtitle}>Add content to begin playback</Text>

          <View style={styles.emptySetupRow}>
            <View style={styles.emptyQrPanel}>
              {cmsInfo.qrCode ? (
                <Image source={{ uri: cmsInfo.qrCode }} style={styles.emptyQrCode} />
              ) : (
                <View style={styles.emptyQrLoading}><ActivityIndicator color="#38bdf8" /></View>
              )}
              <Text style={styles.emptyQrLabel}>SCAN TO OPEN CMS</Text>
              <Text style={styles.emptyQrUrl}>{cmsInfo.url || 'Connecting to CMS...'}</Text>
            </View>
            <View style={styles.emptyInstructionPanel}>
              <Text style={styles.emptyInstructionTitle}>Get started in 3 steps</Text>
              <Text style={styles.emptyInstructionText}>1. Connect your phone or computer to the same Wi-Fi as this TV.</Text>
              <Text style={styles.emptyInstructionText}>2. Scan the QR code or open the address shown beside it.</Text>
              <Text style={styles.emptyInstructionText}>3. Upload images or videos. Playback starts automatically.</Text>
              <Text style={styles.emptyUsbHint}>USB option: copy media to nvsign/section1, then insert the drive.</Text>
            </View>
          </View>

          <View style={styles.pathBox}>
            <Text style={styles.pathLabel}>📁 Media Folder Location on TV:</Text>
            <Text style={styles.pathText}>{activeAdsDir}</Text>
          </View>

          <View style={{ width: '100%', marginVertical: 12, paddingHorizontal: 8 }}>
            <Text style={styles.emptyStepsTitle}>
              💡 Easy Ways to Add Media Content:
            </Text>
            <Text style={styles.emptyStepsText}>
              1. Open the CMS portal from a phone or computer on the same Wi-Fi:{'\n'}
                 👉 <Text style={{ color: '#4ade80', fontWeight: 'bold' }}>{cmsInfo.url || 'http://<TV_IP>:9090'}</Text>{'\n'}
              2. Insert a USB drive containing your media.{'\n'}
              3. Or copy content into section folders at '{activeAdsDir}'.
            </Text>
          </View>

          <Pressable
            focusable
            hasTVPreferredFocus
            onPress={scanFolder}
            style={({ pressed, focused }: any) => [
              styles.btn,
              focused && styles.btnFocused,
              pressed && styles.btnPressed,
              { marginTop: 8 }
            ]}
          >
            <Text style={styles.btnText}>🔄 Refresh / Scan Media Files</Text>
          </Pressable>
        </View>
      </View>
    );
  }

  // The CMS rotation mapping below stays unchanged. Use the real full-sensor
  // display bounds so the player also fills a TV forced to portrait by ADB.
  const screenWidth = playerWindowDimensions.width || Dimensions.get('window').width || 1920;
  const screenHeight = playerWindowDimensions.height || Dimensions.get('window').height || 1080;
  const width = screenWidth;
  const height = screenHeight;
  const orientation = config.orientation || 'horizontal';
  const isVertical = orientation === 'vertical' || orientation === 'reverse-vertical';
  const rotation = orientation === 'vertical'
    ? '90deg'
    : orientation === 'reverse-vertical'
      ? '-90deg'
      : orientation === 'reverse-horizontal'
        ? '180deg'
        : '0deg';
  const containerWidth = isVertical ? height : width;
  const containerHeight = isVertical ? width : height;
  const topOffset = (height - containerHeight) / 2;
  const leftOffset = (width - containerWidth) / 2;

  return (
    <View
      style={{
        width: containerWidth,
        height: containerHeight,
        position: 'absolute',
        top: topOffset,
        left: leftOffset,
        transform: [{ rotate: rotation }],
        backgroundColor: '#000000',
        overflow: 'hidden',
      }}
    >
      <View style={styles.viewerContainer}>
        <StatusBar hidden />

        {/* Top Ticker (Flex layout flow above media content so no content overlap occurs) */}
        {!!config.tickerText && config.tickerPosition === 'top' && (
          <ContinuousTicker
            text={config.tickerText}
            color={config.tickerTextColor}
            backgroundColor={config.tickerBgColor !== 'transparent' ? config.tickerBgColor : '#000000'}
            fontSize={config.tickerFontSize || 16}
            fontFamily={config.tickerFontFamily}
            speed={config.tickerSpeed || 5}
            direction={config.tickerDirection || 'left'}
            paused={configOpen}
          />
        )}

        {/* Emergency Alert Flashing Overlay */}
        <EmergencyAlertOverlay
          alert={emergencyAlert}
          onClear={() => setEmergencyAlert({ active: false, type: '', title: '', message: '', sound: false })}
        />

        {/* Custom Visual Drag-and-Drop Canvas Layout Mode */}
        {customLayout.enabled && customLayout.zones && customLayout.zones.length > 0 ? (
          <View style={{ flex: 1, width: '100%', height: '100%', position: 'relative' }}>
            {customLayout.zones.map((z, idx) => {
              const zoneStyle: ViewStyle = {
                position: 'absolute',
                left: `${z.left}%` as any,
                top: `${z.top}%` as any,
                width: `${z.width}%` as any,
                height: `${z.height}%` as any,
                zIndex: z.zIndex || 1,
              };

              if (z.type === 'media') {
                const targetSec = sections[idx % sections.length] || sections[0];
                return (
                  <View key={z.id || idx} style={zoneStyle}>
                    {targetSec ? (
                      <SectionPlayer
                        section={targetSec}
                        sectionIndex={idx % sections.length}
                        totalSections={sections.length}
                        config={config}
                        configOpen={configOpen}
                        onOpenConfig={() => {
                          setConfigOpen(true);
                          setTempConfig(config);
                          setFocusedIndex(0);
                        }}
                      />
                    ) : null}
                  </View>
                );
              } else if (z.type === 'stream') {
                return (
                  <View key={z.id || idx} style={zoneStyle}>
                    <StreamPlayer streamUrl={config.liveStreamUrl || ''} resizeMode={resizeMode} />
                  </View>
                );
              } else if (z.type === 'clock') {
                return (
                  <View key={z.id || idx} style={zoneStyle}>
                    <ClockWidget config={config} />
                  </View>
                );
              } else if (z.type === 'weather') {
                return (
                  <View key={z.id || idx} style={zoneStyle}>
                    <WeatherWidget config={config} />
                  </View>
                );
              }
              return null;
            })}
          </View>
        ) : sections.length > 0 ? (
          <View style={styles.splitScreenContainer}>
            {sections.length === 1 ? (
              <View style={styles.fullCell}>
                <SectionPlayer
                  section={sections[0]}
                  sectionIndex={0}
                  totalSections={1}
                  config={config}
                  configOpen={configOpen}
                  onOpenConfig={() => {
                    setConfigOpen(true);
                    setTempConfig(config);
                    setFocusedIndex(0);
                  }}
                />
              </View>
            ) : sections.length === 2 ? (
              config.layoutMode === 'stack_vertical' ? (
                <View style={styles.columnLayout}>
                  <View style={[styles.flexCell, { flex: getRatioFlex(config.sectionRatio, true), borderBottomWidth: 2, borderColor: '#3b82f6' }]}>
                    <SectionPlayer
                      section={sections[0]}
                      sectionIndex={0}
                      totalSections={2}
                      config={config}
                      configOpen={configOpen}
                      onOpenConfig={() => {
                        setConfigOpen(true);
                        setTempConfig(config);
                        setFocusedIndex(0);
                      }}
                    />
                  </View>
                  <View style={[styles.flexCell, { flex: getRatioFlex(config.sectionRatio, false) }]}>
                    <SectionPlayer
                      section={sections[1]}
                      sectionIndex={1}
                      totalSections={2}
                      config={config}
                      configOpen={configOpen}
                      onOpenConfig={() => {
                        setConfigOpen(true);
                        setTempConfig(config);
                        setFocusedIndex(0);
                      }}
                    />
                  </View>
                </View>
              ) : (
                <View style={styles.rowLayout}>
                  <View style={[styles.flexCell, { flex: getRatioFlex(config.sectionRatio, true), borderRightWidth: 2, borderColor: '#3b82f6' }]}>
                    <SectionPlayer
                      section={sections[0]}
                      sectionIndex={0}
                      totalSections={2}
                      config={config}
                      configOpen={configOpen}
                      onOpenConfig={() => {
                        setConfigOpen(true);
                        setTempConfig(config);
                        setFocusedIndex(0);
                      }}
                    />
                  </View>
                  <View style={[styles.flexCell, { flex: getRatioFlex(config.sectionRatio, false) }]}>
                    <SectionPlayer
                      section={sections[1]}
                      sectionIndex={1}
                      totalSections={2}
                      config={config}
                      configOpen={configOpen}
                      onOpenConfig={() => {
                        setConfigOpen(true);
                        setTempConfig(config);
                        setFocusedIndex(0);
                      }}
                    />
                  </View>
                </View>
              )
            ) : sections.length === 3 ? (
              config.layoutMode === 'stack_vertical' ? (
                <View style={styles.columnLayout}>
                  {sections.map((sec, idx) => (
                    <View
                      key={sec.id || idx}
                      style={[
                        styles.flexCell,
                        { borderBottomWidth: idx < 2 ? 2 : 0, borderColor: '#3b82f6' },
                      ]}
                    >
                      <SectionPlayer
                        section={sec}
                        sectionIndex={idx}
                        totalSections={3}
                        config={config}
                        configOpen={configOpen}
                        onOpenConfig={() => {
                          setConfigOpen(true);
                          setTempConfig(config);
                          setFocusedIndex(0);
                        }}
                      />
                    </View>
                  ))}
                </View>
              ) : config.layoutMode === 'stack_horizontal' ? (
                <View style={styles.rowLayout}>
                  {sections.map((sec, idx) => (
                    <View
                      key={sec.id || idx}
                      style={[
                        styles.flexCell,
                        { borderRightWidth: idx < 2 ? 2 : 0, borderColor: '#3b82f6' },
                      ]}
                    >
                      <SectionPlayer
                        section={sec}
                        sectionIndex={idx}
                        totalSections={3}
                        config={config}
                        configOpen={configOpen}
                        onOpenConfig={() => {
                          setConfigOpen(true);
                          setTempConfig(config);
                          setFocusedIndex(0);
                        }}
                      />
                    </View>
                  ))}
                </View>
              ) : config.layoutMode === 'top1_bottom2' ? (
                <View style={styles.columnLayout}>
                  <View style={[styles.flexCell, { flex: getRatioFlex(config.sectionRatio, true), width: '100%', borderBottomWidth: 2, borderColor: '#3b82f6' }]}>
                    <SectionPlayer
                      section={sections[0]}
                      sectionIndex={0}
                      totalSections={3}
                      config={config}
                      configOpen={configOpen}
                      onOpenConfig={() => {
                        setConfigOpen(true);
                        setTempConfig(config);
                        setFocusedIndex(0);
                      }}
                    />
                  </View>
                  <View style={[styles.rowLayout, { flex: getRatioFlex(config.sectionRatio, false) }]}>
                    {sections.slice(1, 3).map((sec, idx) => (
                      <View
                        key={sec.id || (idx + 1)}
                        style={[
                          styles.flexCell,
                          { borderRightWidth: idx === 0 ? 2 : 0, borderColor: '#3b82f6' },
                        ]}
                      >
                        <SectionPlayer
                          section={sec}
                          sectionIndex={idx + 1}
                          totalSections={3}
                          config={config}
                          configOpen={configOpen}
                          onOpenConfig={() => {
                            setConfigOpen(true);
                            setTempConfig(config);
                            setFocusedIndex(0);
                          }}
                        />
                      </View>
                    ))}
                  </View>
                </View>
              ) : (
                /* top2_bottom1 or auto */
                <View style={styles.columnLayout}>
                  <View style={[styles.rowLayout, { flex: getRatioFlex(config.sectionRatio, true), borderBottomWidth: 2, borderColor: '#3b82f6' }]}>
                    {sections.slice(0, 2).map((sec, idx) => (
                      <View
                        key={sec.id || idx}
                        style={[
                          styles.flexCell,
                          { borderRightWidth: idx === 0 ? 2 : 0, borderColor: '#3b82f6' },
                        ]}
                      >
                        <SectionPlayer
                          section={sec}
                          sectionIndex={idx}
                          totalSections={3}
                          config={config}
                          configOpen={configOpen}
                          onOpenConfig={() => {
                            setConfigOpen(true);
                            setTempConfig(config);
                            setFocusedIndex(0);
                          }}
                        />
                      </View>
                    ))}
                  </View>
                  <View style={[styles.flexCell, { flex: getRatioFlex(config.sectionRatio, false), width: '100%' }]}>
                    <SectionPlayer
                      section={sections[2] || sections[0]}
                      sectionIndex={2}
                      totalSections={3}
                      config={config}
                      configOpen={configOpen}
                      onOpenConfig={() => {
                        setConfigOpen(true);
                        setTempConfig(config);
                        setFocusedIndex(0);
                      }}
                    />
                  </View>
                </View>
              )
            ) : (
              /* 4 or more sections */
              config.layoutMode === 'stack_vertical' ? (
                <View style={styles.columnLayout}>
                  {sections.map((sec, idx) => (
                    <View
                      key={sec.id || idx}
                      style={[
                        styles.flexCell,
                        { borderBottomWidth: idx < sections.length - 1 ? 2 : 0, borderColor: '#3b82f6' },
                      ]}
                    >
                      <SectionPlayer
                        section={sec}
                        sectionIndex={idx}
                        totalSections={sections.length}
                        config={config}
                        configOpen={configOpen}
                        onOpenConfig={() => {
                          setConfigOpen(true);
                          setTempConfig(config);
                          setFocusedIndex(0);
                        }}
                      />
                    </View>
                  ))}
                </View>
              ) : config.layoutMode === 'stack_horizontal' ? (
                <View style={styles.rowLayout}>
                  {sections.map((sec, idx) => (
                    <View
                      key={sec.id || idx}
                      style={[
                        styles.flexCell,
                        { borderRightWidth: idx < sections.length - 1 ? 2 : 0, borderColor: '#3b82f6' },
                      ]}
                    >
                      <SectionPlayer
                        section={sec}
                        sectionIndex={idx}
                        totalSections={sections.length}
                        config={config}
                        configOpen={configOpen}
                        onOpenConfig={() => {
                          setConfigOpen(true);
                          setTempConfig(config);
                          setFocusedIndex(0);
                        }}
                      />
                    </View>
                  ))}
                </View>
              ) : (
                /* 2x2 Grid Layout with Ratios */
                <View style={styles.columnLayout}>
                  <View style={[styles.rowLayout, { flex: getRatioFlex(config.sectionRatio, true), borderBottomWidth: 2, borderColor: '#3b82f6' }]}>
                    {sections.slice(0, 2).map((sec, idx) => (
                      <View
                        key={sec.id || idx}
                        style={[
                          styles.flexCell,
                          { borderRightWidth: idx === 0 ? 2 : 0, borderColor: '#3b82f6' },
                        ]}
                      >
                        <SectionPlayer
                          section={sec}
                          sectionIndex={idx}
                          totalSections={sections.length}
                          config={config}
                          configOpen={configOpen}
                          onOpenConfig={() => {
                            setConfigOpen(true);
                            setTempConfig(config);
                            setFocusedIndex(0);
                          }}
                        />
                      </View>
                    ))}
                  </View>
                  <View style={[styles.rowLayout, { flex: getRatioFlex(config.sectionRatio, false) }]}>
                    {sections.slice(2, 4).map((sec, idx) => (
                      <View
                        key={sec.id || (idx + 2)}
                        style={[
                          styles.flexCell,
                          { borderRightWidth: idx === 0 ? 2 : 0, borderColor: '#3b82f6' },
                        ]}
                      >
                        <SectionPlayer
                          section={sec}
                          sectionIndex={idx + 2}
                          totalSections={sections.length}
                          config={config}
                          configOpen={configOpen}
                          onOpenConfig={() => {
                            setConfigOpen(true);
                            setTempConfig(config);
                            setFocusedIndex(0);
                          }}
                        />
                      </View>
                    ))}
                  </View>
                </View>
              )
            )}
          </View>
        ) : null}

        {/* Bottom Ticker (Flex layout flow below media content so no content overlap occurs) */}
        {!!config.tickerText && config.tickerPosition === 'bottom' && (
          <ContinuousTicker
            text={config.tickerText}
            color={config.tickerTextColor}
            backgroundColor={config.tickerBgColor !== 'transparent' ? config.tickerBgColor : '#000000'}
            fontSize={config.tickerFontSize || 16}
            fontFamily={config.tickerFontFamily}
            speed={config.tickerSpeed || 5}
            direction={config.tickerDirection || 'left'}
            paused={configOpen}
          />
        )}

        {/* Middle Ticker Overlay (Center Screen) */}
        {!!config.tickerText && config.tickerPosition === 'middle' && (
          <ContinuousTicker
            text={config.tickerText}
            color={config.tickerTextColor}
            backgroundColor={config.tickerBgColor !== 'transparent' ? config.tickerBgColor : 'rgba(0,0,0,0.85)'}
            fontSize={config.tickerFontSize || 16}
            fontFamily={config.tickerFontFamily}
            speed={config.tickerSpeed || 5}
            direction={config.tickerDirection || 'left'}
            paused={configOpen}
            overlay
          />
        )}

        {/* Semi-transparent Mini QR Code Overlay Badge with IP:Port Display */}
        {!!cmsInfo.qrCode && config.showQrCode !== false && (
          <View
            style={{
              position: 'absolute',
              top: config.tickerPosition === 'top' && !!config.tickerText ? 40 : 12,
              right: 12,
              backgroundColor: 'rgba(0, 0, 0, 0.75)',
              padding: 5,
              borderRadius: 8,
              zIndex: 9999,
              alignItems: 'center',
              width: 65,
              borderWidth: 1,
              borderColor: 'rgba(255, 255, 255, 0.25)',
            }}
          >
            <Image
              source={{ uri: cmsInfo.qrCode }}
              style={{ width: 55, height: 55, borderRadius: 4 }}
              resizeMode="contain"
            />
            {!!cmsInfo.url && (
              <Text
                numberOfLines={1}
                adjustsFontSizeToFit
                style={{
                  color: '#38bdf8',
                  fontSize: 8.5,
                  fontWeight: '700',
                  marginTop: 3,
                  width: 55,
                  textAlign: 'center',
                }}
              >
                {cmsInfo.url.replace(/^https?:\/\//i, '')}
              </Text>
            )}
          </View>
        )}

        {/* Config Settings Full Screen Page */}
        {configOpen && (
          <View style={styles.configPage}>
            <Text style={styles.configPageHeader}>⚙️ Configuration Settings</Text>

            <ScrollView
              style={styles.configPageScroll}
              keyboardShouldPersistTaps="handled"
              focusable={true}
              contentContainerStyle={styles.configPageContent}
            >
              {/* Slide Duration */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Image Slide Duration:</Text>
                <View style={styles.singleColumn}>
                  {DURATION_OPTIONS.map((opt, idx) => (
                    <Pressable
                      key={opt.value}
                      id={`duration-${opt.value}`}
                      focusable={true}
                      hasTVPreferredFocus={idx === 0}
                      onFocus={() => setFocusedId(`duration-${opt.value}`)}
                      onBlur={() => setFocusedId('')}
                      onPress={() => {
                        setTempConfig({ ...tempConfig, slideDuration: opt.value });
                        setFocusedIndex(idx);
                      }}
                      style={({ pressed, focused }: any) => [
                        styles.choiceBtnSingle,
                        tempConfig.slideDuration === opt.value && styles.choiceActive,
                        (focused || focusedId === `duration-${opt.value}`) && styles.btnFocused,
                        pressed && styles.btnPressed,
                      ]}
                    >
                      <Text
                        style={[
                          styles.choiceText,
                          tempConfig.slideDuration === opt.value && styles.choiceTextActive,
                        ]}
                      >
                        {opt.label}
                      </Text>
                    </Pressable>
                  ))}
                </View>
              </View>

              {/* Ticker Text */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Ticker Text:</Text>
                <TextInput
                  id="ticker-text"
                  style={styles.textInput}
                  value={tempConfig.tickerText}
                  onChangeText={(text) => setTempConfig({ ...tempConfig, tickerText: text })}
                  placeholder="Enter ticker text..."
                  placeholderTextColor="#64748b"
                  multiline={false}
                  focusable={true}
                  selectTextOnFocus={true}
                  onFocus={() => setFocusedId('ticker-text')}
                  onBlur={() => setFocusedId('')}
                />
              </View>

              {/* Ticker Text Color */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Ticker Text Color:</Text>
                <View style={styles.colorGrid}>
                  {COLOR_OPTIONS.map((color) => (
                    <Pressable
                      key={color.value}
                      id={`textcolor-${color.value}`}
                      focusable={true}
                      onFocus={() => setFocusedId(`textcolor-${color.value}`)}
                      onBlur={() => setFocusedId('')}
                      onPress={() => {
                        setTempConfig({ ...tempConfig, tickerTextColor: color.value });
                        setCustomTextColor(color.value);
                      }}
                      style={({ pressed, focused }: any) => [
                        styles.colorBtn,
                        tempConfig.tickerTextColor === color.value && styles.colorActive,
                        (focused || focusedId === `textcolor-${color.value}`) && styles.btnFocused,
                        pressed && styles.btnPressed,
                        { backgroundColor: color.value },
                      ]}
                    />
                  ))}
                </View>
                <TextInput
                  id="textcolor-hex"
                  style={styles.hexInput}
                  value={customTextColor}
                  onChangeText={(text) => {
                    setCustomTextColor(text);
                    if (/^#[0-9A-Fa-f]{6}$/.test(text)) {
                      setTempConfig({ ...tempConfig, tickerTextColor: text });
                    }
                  }}
                  placeholder="#FFFFFF"
                  placeholderTextColor="#64748b"
                  maxLength={7}
                  focusable={true}
                  selectTextOnFocus={true}
                  onFocus={() => setFocusedId('textcolor-hex')}
                  onBlur={() => setFocusedId('')}
                />
              </View>

              {/* Ticker Background Color */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Ticker Background Color:</Text>
                <View style={styles.colorGrid}>
                  {BG_COLOR_OPTIONS.map((color) => (
                    <Pressable
                      key={color.value}
                      id={`bgcolor-${color.value}`}
                      focusable={true}
                      onFocus={() => setFocusedId(`bgcolor-${color.value}`)}
                      onBlur={() => setFocusedId('')}
                      onPress={() => {
                        setTempConfig({ ...tempConfig, tickerBgColor: color.value });
                        setCustomBgColor(color.value);
                      }}
                      style={({ pressed, focused }: any) => [
                        styles.colorBtn,
                        tempConfig.tickerBgColor === color.value && styles.colorActive,
                        (focused || focusedId === `bgcolor-${color.value}`) && styles.btnFocused,
                        pressed && styles.btnPressed,
                        { backgroundColor: color.value === 'transparent' ? '#333' : color.value },
                      ]}
                    >
                      {color.value === 'transparent' && (
                        <Text style={styles.transparentLabel}>T</Text>
                      )}
                    </Pressable>
                  ))}
                </View>
                <TextInput
                  id="bgcolor-hex"
                  style={styles.hexInput}
                  value={customBgColor}
                  onChangeText={(text) => {
                    setCustomBgColor(text);
                    if (/^#[0-9A-Fa-f]{6}$/.test(text) || text === 'transparent') {
                      setTempConfig({ ...tempConfig, tickerBgColor: text });
                    }
                  }}
                  placeholder="#000000"
                  placeholderTextColor="#64748b"
                  maxLength={11}
                  focusable={true}
                  selectTextOnFocus={true}
                  onFocus={() => setFocusedId('bgcolor-hex')}
                  onBlur={() => setFocusedId('')}
                />
              </View>

              {/* Ticker Position */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Ticker Position:</Text>
                <View style={styles.singleColumn}>
                  {TICKER_POSITIONS.map((pos) => (
                    <Pressable
                      key={pos.value}
                      id={`position-${pos.value}`}
                      focusable={true}
                      onFocus={() => setFocusedId(`position-${pos.value}`)}
                      onBlur={() => setFocusedId('')}
                      onPress={() => setTempConfig({ ...tempConfig, tickerPosition: pos.value as 'top' | 'bottom' })}
                      style={({ pressed, focused }: any) => [
                        styles.choiceBtnSingle,
                        tempConfig.tickerPosition === pos.value && styles.choiceActive,
                        (focused || focusedId === `position-${pos.value}`) && styles.btnFocused,
                        pressed && styles.btnPressed,
                      ]}
                    >
                      <Text
                        style={[
                          styles.choiceText,
                          tempConfig.tickerPosition === pos.value && styles.choiceTextActive,
                        ]}
                      >
                        {pos.label}
                      </Text>
                    </Pressable>
                  ))}
                </View>
              </View>

              {/* Ticker Font Size */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Ticker Font Size:</Text>
                <View style={styles.singleColumn}>
                  {FONT_SIZE_OPTIONS.map((size) => (
                    <Pressable
                      key={size.value}
                      id={`fontsize-${size.value}`}
                      focusable={true}
                      onFocus={() => setFocusedId(`fontsize-${size.value}`)}
                      onBlur={() => setFocusedId('')}
                      onPress={() => setTempConfig({ ...tempConfig, tickerFontSize: size.value })}
                      style={({ pressed, focused }: any) => [
                        styles.choiceBtnSingle,
                        tempConfig.tickerFontSize === size.value && styles.choiceActive,
                        (focused || focusedId === `fontsize-${size.value}`) && styles.btnFocused,
                        pressed && styles.btnPressed,
                      ]}
                    >
                      <Text
                        style={[
                          styles.choiceText,
                          tempConfig.tickerFontSize === size.value && styles.choiceTextActive,
                        ]}
                      >
                        {size.label}
                      </Text>
                    </Pressable>
                  ))}
                </View>
              </View>


              {/* Pendrive Option */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Use Pendrive (USB) for Ads:</Text>
                <View style={styles.singleColumn}>
                  <Pressable
                    id="pendrive-on"
                    focusable={true}
                    onFocus={() => setFocusedId('pendrive-on')}
                    onBlur={() => setFocusedId('')}
                    onPress={() => setTempConfig({ ...tempConfig, usePendrive: true })}
                    style={({ pressed, focused }: any) => [
                      styles.choiceBtnSingle,
                      tempConfig.usePendrive && styles.choiceActive,
                      (focused || focusedId === 'pendrive-on') && styles.btnFocused,
                      pressed && styles.btnPressed,
                    ]}
                  >
                    <Text
                      style={[
                        styles.choiceText,
                        tempConfig.usePendrive && styles.choiceTextActive,
                      ]}
                    >
                      YES
                    </Text>
                  </Pressable>
                  <Pressable
                    id="pendrive-off"
                    focusable={true}
                    onFocus={() => setFocusedId('pendrive-off')}
                    onBlur={() => setFocusedId('')}
                    onPress={() => setTempConfig({ ...tempConfig, usePendrive: false })}
                    style={({ pressed, focused }: any) => [
                      styles.choiceBtnSingle,
                      !tempConfig.usePendrive && styles.choiceActive,
                      (focused || focusedId === 'pendrive-off') && styles.btnFocused,
                      pressed && styles.btnPressed,
                    ]}
                  >
                    <Text
                      style={[
                        styles.choiceText,
                        !tempConfig.usePendrive && styles.choiceTextActive,
                      ]}
                    >
                      NO
                    </Text>
                  </Pressable>
                </View>
              </View>

              {/* Screen Orientation Option */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>Screen Orientation:</Text>
                <View style={styles.singleColumn}>
                  {ORIENTATION_OPTIONS.map((opt) => (
                    <Pressable
                      key={opt.value}
                      id={`orient-${opt.value}`}
                      focusable={true}
                      onFocus={() => setFocusedId(`orient-${opt.value}`)}
                      onBlur={() => setFocusedId('')}
                      onPress={() => setTempConfig({ ...tempConfig, orientation: opt.value as any })}
                      style={({ pressed, focused }: any) => [
                        styles.choiceBtnSingle,
                        (tempConfig.orientation || 'horizontal') === opt.value && styles.choiceActive,
                        (focused || focusedId === `orient-${opt.value}`) && styles.btnFocused,
                        pressed && styles.btnPressed,
                      ]}
                    >
                      <Text
                        style={[
                          styles.choiceText,
                          (tempConfig.orientation || 'horizontal') === opt.value && styles.choiceTextActive,
                        ]}
                      >
                        {opt.label}
                      </Text>
                    </Pressable>
                  ))}
                </View>
              </View>

              {/* Kiosk Lock Mode Option */}
              <View style={styles.settingSectionSingle}>
                <Text style={styles.sectionTitle}>🔒 Kiosk Lock Mode (Prevent Sleep & Exit):</Text>
                <View style={styles.singleColumn}>
                  <Pressable
                    id="kiosk-locked"
                    focusable={true}
                    onFocus={() => setFocusedId('kiosk-locked')}
                    onBlur={() => setFocusedId('')}
                    onPress={() => setTempConfig({ ...tempConfig, kioskMode: true })}
                    style={({ pressed, focused }: any) => [
                      styles.choiceBtnSingle,
                      tempConfig.kioskMode !== false && styles.choiceActive,
                      (focused || focusedId === 'kiosk-locked') && styles.btnFocused,
                      pressed && styles.btnPressed,
                    ]}
                  >
                    <Text
                      style={[
                        styles.choiceText,
                        tempConfig.kioskMode !== false && styles.choiceTextActive,
                      ]}
                    >
                      🔒 LOCKED (ON - Keep Awake & Block Exit)
                    </Text>
                  </Pressable>
                  <Pressable
                    id="kiosk-unlocked"
                    focusable={true}
                    onFocus={() => setFocusedId('kiosk-unlocked')}
                    onBlur={() => setFocusedId('')}
                    onPress={() => setTempConfig({ ...tempConfig, kioskMode: false })}
                    style={({ pressed, focused }: any) => [
                      styles.choiceBtnSingle,
                      tempConfig.kioskMode === false && styles.choiceActive,
                      (focused || focusedId === 'kiosk-unlocked') && styles.btnFocused,
                      pressed && styles.btnPressed,
                    ]}
                  >
                    <Text
                      style={[
                        styles.choiceText,
                        tempConfig.kioskMode === false && styles.choiceTextActive,
                      ]}
                    >
                      🔓 UNLOCKED (OFF - Allow Settings & Exit)
                    </Text>
                  </Pressable>
                </View>
              </View>

              {/* Actions Row */}
              <View style={styles.actionRowSingle}>
                <Pressable
                  id="save-btn"
                  focusable={true}
                  onFocus={() => setFocusedId('save-btn')}
                  onBlur={() => setFocusedId('')}
                  onPress={() => {
                    saveConfig(tempConfig);
                    setConfigOpen(false);
                    scanFolder();
                  }}
                  style={({ pressed, focused }: any) => [
                    styles.btnPrimarySingle,
                    (focused || focusedId === 'save-btn') && styles.btnFocused,
                    pressed && styles.btnPressed,
                  ]}
                >
                  <Text style={styles.btnText}>💾 Save & Close</Text>
                </Pressable>

                <Pressable
                  id="cancel-btn"
                  focusable={true}
                  onFocus={() => setFocusedId('cancel-btn')}
                  onBlur={() => setFocusedId('')}
                  onPress={() => {
                    setConfigOpen(false);
                    setTempConfig(config); // Revert to actual config on cancel
                  }}
                  style={({ pressed, focused }: any) => [
                    styles.btnSecondarySingle,
                    (focused || focusedId === 'cancel-btn') && styles.btnFocused,
                    pressed && styles.btnPressed,
                  ]}
                >
                  <Text style={styles.btnText}>❌ Cancel</Text>
                </Pressable>
              </View>

              <Text style={styles.hintFooter}>
                Use remote D-pad to navigate. Press BACK to dismiss.
              </Text>
            </ScrollView>
          </View>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  transitionFrame: {
    flex: 1,
    width: '100%',
    height: '100%',
    overflow: 'hidden',
  },
  darkContainer: {
    flex: 1,
    backgroundColor: '#0c0d12',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
    margin: 0,
  },
  statusText: {
    marginTop: 16,
    color: '#94a3b8',
    fontSize: 16,
    fontWeight: '500',
  },
  pdfProgressCard: { width: '70%', maxWidth: 620, marginTop: 18, alignItems: 'center' },
  pdfProgressTitle: { color: '#f8fafc', fontSize: 18, fontWeight: '700' },
  pdfProgressFile: { color: '#94a3b8', fontSize: 13, marginTop: 8, marginBottom: 12 },
  pdfProgressTrack: { width: '100%', height: 14, backgroundColor: '#1e293b', borderRadius: 7, overflow: 'hidden' },
  pdfProgressFill: { height: '100%', backgroundColor: '#6366f1', borderRadius: 7 },
  pdfProgressText: { color: '#e2e8f0', fontSize: 16, fontWeight: '700', marginTop: 9 },
  licenseCard: {
    width: '80%',
    backgroundColor: '#171923',
    borderRadius: 16,
    padding: 32,
    borderWidth: 1,
    borderColor: '#2d3748',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.3,
    shadowRadius: 20,
    elevation: 8,
  },
  connectTitle: {
    fontSize: 24,
    color: '#f8fafc',
    fontWeight: 'bold',
    letterSpacing: 0.5,
    marginBottom: 8,
  },
  licenseHint: {
    fontSize: 14,
    color: '#94a3b8',
    textAlign: 'center',
    lineHeight: 18,
    marginBottom: 24,
  },
  licenseRow: {
    width: '100%',
    marginBottom: 16,
  },
  licenseLabel: {
    color: '#64748b',
    fontSize: 12,
    fontWeight: '600',
    textTransform: 'uppercase',
    marginBottom: 4,
  },
  licenseValue: {
    color: '#38bdf8',
    fontSize: 16,
    fontWeight: 'bold',
    backgroundColor: '#0d0e15',
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  licenseInput: {
    backgroundColor: '#0d0e15',
    color: '#f8fafc',
    fontSize: 16,
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  licenseInputFocused: {
    borderColor: '#38bdf8',
  },
  licenseBtn: {
    backgroundColor: '#6366f1',
    paddingVertical: 14,
    paddingHorizontal: 32,
    borderRadius: 8,
    marginTop: 8,
    minWidth: 200,
  },
  licenseBtnFocused: {
    backgroundColor: '#4f46e5',
  },
  licenseBtnText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    textAlign: 'center',
  },
  licenseStatus: {
    marginTop: 16,
    color: '#94a3b8',
    fontSize: 14,
    textAlign: 'center',
  },
  instructionCard: {
    width: '80%',
    backgroundColor: '#171923',
    borderRadius: 16,
    padding: 32,
    borderWidth: 1,
    borderColor: '#2d3748',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.3,
    shadowRadius: 20,
    elevation: 8,
  },
  emojiTitle: {
    color: '#ffffff',
    fontSize: 28,
    fontWeight: '700',
    letterSpacing: 0.4,
    marginBottom: 8,
  },
  titleText: {
    fontSize: 24,
    color: '#f8fafc',
    fontWeight: 'bold',
    letterSpacing: 0.5,
  },
  subtitleText: {
    fontSize: 16,
    color: '#ef4444',
    fontWeight: '600',
    marginTop: 4,
    marginBottom: 16,
  },
  readyPill: {
    backgroundColor: 'rgba(34, 197, 94, 0.14)',
    borderColor: 'rgba(74, 222, 128, 0.45)',
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 5,
    marginBottom: 10,
  },
  readyPillText: {
    color: '#4ade80',
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 1.2,
  },
  emptyScreenTitle: {
    color: '#f8fafc',
    fontSize: 26,
    fontWeight: '800',
    letterSpacing: 0.2,
  },
  emptyScreenSubtitle: {
    color: '#94a3b8',
    fontSize: 15,
    marginTop: 5,
    marginBottom: 18,
  },
  emptySetupRow: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'stretch',
    gap: 16,
    marginBottom: 16,
  },
  emptyQrPanel: {
    width: 176,
    padding: 12,
    alignItems: 'center',
    backgroundColor: '#ffffff',
    borderRadius: 12,
  },
  emptyQrCode: {
    width: 116,
    height: 116,
    resizeMode: 'contain',
  },
  emptyQrLoading: {
    width: 116,
    height: 116,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyQrLabel: {
    color: '#0f172a',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 0.8,
    marginTop: 8,
  },
  emptyQrUrl: {
    color: '#2563eb',
    fontSize: 10,
    fontWeight: '700',
    marginTop: 3,
    textAlign: 'center',
  },
  emptyInstructionPanel: {
    flex: 1,
    justifyContent: 'center',
    backgroundColor: '#101827',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#243247',
    padding: 16,
  },
  emptyInstructionTitle: {
    color: '#f8fafc',
    fontSize: 16,
    fontWeight: '800',
    marginBottom: 10,
  },
  emptyInstructionText: {
    color: '#cbd5e1',
    fontSize: 12,
    lineHeight: 18,
    marginBottom: 4,
  },
  emptyUsbHint: {
    color: '#7dd3fc',
    fontSize: 11,
    lineHeight: 16,
    marginTop: 8,
  },
  emptyStepsTitle: {
    color: '#38bdf8',
    fontWeight: '800',
    fontSize: 12,
    letterSpacing: 0.9,
    marginBottom: 10,
  },
  emptyStepsText: {
    color: '#cbd5e1',
    fontSize: 13,
    lineHeight: 20,
  },
  pathBox: {
    width: '100%',
    backgroundColor: '#0d0e15',
    borderRadius: 8,
    padding: 12,
    borderWidth: 1,
    borderColor: '#1e293b',
    marginBottom: 16,
    alignItems: 'center',
  },
  pathLabel: {
    color: '#64748b',
    fontSize: 11,
    fontWeight: '600',
    textTransform: 'uppercase',
  },
  pathText: {
    color: '#38bdf8',
    fontSize: 14,
    fontWeight: 'bold',
    marginTop: 2,
  },
  permissionSetupHint: {
    color: '#94a3b8',
    fontSize: 12,
    textAlign: 'center',
    lineHeight: 18,
    marginTop: 18,
    maxWidth: 440,
  },
  infoText: {
    fontSize: 13,
    color: '#94a3b8',
    textAlign: 'center',
    lineHeight: 18,
    marginBottom: 24,
  },
  viewerContainer: {
    flex: 1,
    width: '100%',
    height: '100%',
    backgroundColor: '#000',
    margin: 0,
    padding: 0,
  },
  splitScreenContainer: {
    flex: 1,
    width: '100%',
    height: '100%',
    backgroundColor: '#000',
  },
  fullCell: {
    flex: 1,
    width: '100%',
    height: '100%',
  },
  rowLayout: {
    flex: 1,
    flexDirection: 'row',
    width: '100%',
    height: '100%',
  },
  columnLayout: {
    flex: 1,
    flexDirection: 'column',
    width: '100%',
    height: '100%',
  },
  flexCell: {
    flex: 1,
    width: '100%',
    height: '100%',
    backgroundColor: '#000',
    overflow: 'hidden',
  },
  emptySectionContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#0f172a',
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  emptySectionText: {
    color: '#64748b',
    fontSize: 16,
    fontWeight: '600',
  },
  loadingFrameContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#090d16',
  },
  loadingFrameText: {
    color: '#94a3b8',
    fontSize: 12,
    marginTop: 8,
  },
  mediaWrapper: {
    flex: 1,
    width: '100%',
    height: '100%',
    justifyContent: 'center',
    alignItems: 'center',
  },
  fullscreenMedia: {
    width: '100%',
    height: '100%',
  },
  okButton: {
    position: 'absolute',
    bottom: 40,
    left: 20,
    backgroundColor: 'rgba(0, 0, 0, 0.7)',
    borderRadius: 8,
    paddingVertical: 12,
    paddingHorizontal: 20,
    borderWidth: 2,
    borderColor: '#3f3f46',
    zIndex: 20,
  },
  okButtonFocused: {
    borderColor: '#3b82f6',
    borderWidth: 4,
    transform: [{ scale: 1.05 }],
  },
  okButtonPressed: {
    opacity: 0.8,
  },
  okButtonText: {
    color: '#ffffff',
    fontSize: 16,
    fontWeight: 'bold',
  },
  overlayBg: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0, 0, 0, 0.75)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  settingsDialog: {
    width: '60%',
    backgroundColor: '#131520',
    borderRadius: 16,
    borderWidth: 1.5,
    borderColor: '#3f3f46',
    padding: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 12 },
    shadowOpacity: 0.5,
    shadowRadius: 24,
    elevation: 12,
  },
  settingsHeader: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#f4f4f5',
    marginBottom: 18,
    textAlign: 'center',
  },
  settingSection: {
    marginBottom: 20,
  },
  sectionTitle: {
    fontSize: 14,
    color: '#a1a1aa',
    fontWeight: '600',
    marginBottom: 8,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    gap: 12,
  },
  durationGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  choiceBtn: {
    flex: 1,
    backgroundColor: '#27272a',
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: 'center',
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  choiceBtnCompact: {
    flex: 1,
    minWidth: '30%',
    backgroundColor: '#27272a',
    borderRadius: 8,
    paddingVertical: 8,
    alignItems: 'center',
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  choiceActive: {
    backgroundColor: '#3f3f46',
    borderColor: '#6366f1',
  },
  choiceText: {
    color: '#a1a1aa',
    fontWeight: '700',
    fontSize: 12,
  },
  choiceTextActive: {
    color: '#ffffff',
  },
  actionRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 12,
    gap: 16,
  },
  btn: {
    backgroundColor: '#6366f1',
    borderRadius: 8,
    paddingVertical: 12,
    paddingHorizontal: 24,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  btnPrimary: {
    flex: 1,
    backgroundColor: '#6366f1',
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  btnSecondary: {
    flex: 1,
    backgroundColor: '#27272a',
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: '#3f3f46',
  },
  btnFocused: {
    borderColor: '#60a5fa',
    borderWidth: 5,
    backgroundColor: 'rgba(59, 130, 246, 0.15)',
    transform: [{ scale: 1.03 }],
    shadowColor: '#3b82f6',
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 0.5,
    shadowRadius: 8,
    elevation: 8,
  },
  btnPressed: {
    opacity: 0.8,
  },
  mediaContainer: {
    flex: 1,
    backgroundColor: '#000',
    position: 'relative',
  },
  settingsButton: {
    position: 'absolute',
    top: 20,
    right: 20,
    backgroundColor: 'rgba(0, 0, 0, 0.6)',
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 16,
    borderWidth: 2,
    borderColor: '#3f3f46',
    zIndex: 10,
  },
  settingsButtonText: {
    color: '#ffffff',
    fontSize: 14,
    fontWeight: 'bold',
  },
  tickerBar: {
    width: '100%',
    justifyContent: 'center',
    overflow: 'hidden',
    position: 'relative',
    zIndex: 10,
    backgroundColor: 'transparent',
  },
  tickerBarOverlay: {
    position: 'absolute',
    top: '50%',
    width: '100%',
    zIndex: 9998,
  },
  tickerTrack: {
    position: 'absolute',
    flexDirection: 'row',
    alignItems: 'center',
  },
  tickerMeasureText: {
    position: 'absolute',
    opacity: 0,
    left: -100000,
    top: 0,
    flexShrink: 0,
  },
  tickerText: {
    fontSize: 16,
    fontWeight: '600',
    includeFontPadding: false,
    textAlignVertical: 'center',
    flexShrink: 0,
    flexWrap: 'nowrap',
  },
  configDialog: {
    width: '65%',
    maxHeight: '80%',
    backgroundColor: '#131520',
    borderRadius: 16,
    borderWidth: 1.5,
    borderColor: '#3f3f46',
    padding: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 12 },
    shadowOpacity: 0.5,
    shadowRadius: 24,
    elevation: 12,
  },
  configHeader: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#f4f4f5',
    marginBottom: 18,
    textAlign: 'center',
  },
  configScroll: {
    maxHeight: 400,
    marginBottom: 16,
  },
  textInput: {
    backgroundColor: '#27272a',
    color: '#f4f4f5',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    borderWidth: 2,
    borderColor: '#3f3f46',
  },
  hexInput: {
    backgroundColor: '#27272a',
    color: '#f4f4f5',
    borderRadius: 8,
    padding: 12,
    fontSize: 14,
    borderWidth: 2,
    borderColor: '#3f3f46',
    marginTop: 8,
    textAlign: 'center',
  },
  colorGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 10,
  },
  colorBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    borderWidth: 2,
    borderColor: 'transparent',
    justifyContent: 'center',
    alignItems: 'center',
  },
  colorActive: {
    borderColor: '#3b82f6',
    borderWidth: 3,
  },
  transparentLabel: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 12,
  },
  btnText: {
    color: '#ffffff',
    fontWeight: 'bold',
    fontSize: 14,
  },
  hintFooter: {
    fontSize: 10,
    color: '#71717a',
    textAlign: 'center',
    marginTop: 18,
  },
  configPage: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: '#131520',
  },
  configPageHeader: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#f4f4f5',
    marginBottom: 20,
    textAlign: 'center',
    marginTop: 20,
  },
  configPageScroll: {
    flex: 1,
  },
  configPageContent: {
    paddingHorizontal: 32,
    paddingBottom: 40,
  },
  settingSectionSingle: {
    marginBottom: 24,
  },
  singleColumn: {
    flexDirection: 'column',
    gap: 12,
  },
  choiceBtnSingle: {
    width: '100%',
    backgroundColor: '#27272a',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
    borderWidth: 1.5,
    borderColor: 'transparent',
  },
  actionRowSingle: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 24,
    gap: 16,
  },
  btnPrimarySingle: {
    flex: 1,
    backgroundColor: '#6366f1',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: 'transparent',
  },
  btnSecondarySingle: {
    flex: 1,
    backgroundColor: '#27272a',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
    borderWidth: 2,
    borderColor: '#3f3f46',
  },
  wizardCard: {
    width: '85%',
    backgroundColor: '#171923',
    borderRadius: 16,
    padding: 24,
    borderWidth: 1.5,
    borderColor: '#2d3748',
    alignItems: 'center',
  },
  wizardEmoji: {
    fontSize: 40,
    marginBottom: 8,
  },
  wizardTitle: {
    fontSize: 22,
    color: '#f8fafc',
    fontWeight: 'bold',
    marginBottom: 6,
  },
  wizardDesc: {
    fontSize: 12,
    color: '#94a3b8',
    textAlign: 'center',
    lineHeight: 18,
    marginBottom: 20,
    paddingHorizontal: 16,
  },
  permRow: {
    width: '100%',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#0d0e15',
    borderRadius: 10,
    padding: 12,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#1e293b',
  },
  permInfo: {
    flex: 1,
    paddingRight: 16,
  },
  permTitle: {
    fontSize: 14,
    fontWeight: 'bold',
    color: '#f8fafc',
    marginBottom: 3,
  },
  permText: {
    fontSize: 10,
    color: '#64748b',
    lineHeight: 14,
  },
  permBtn: {
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 6,
    minWidth: 90,
    alignItems: 'center',
  },
  permBtnAction: {
    backgroundColor: '#6366f1',
  },
  permBtnSuccess: {
    backgroundColor: '#10b981',
  },
  permBtnInfo: {
    backgroundColor: '#3b82f6',
  },
  permBtnText: {
    color: '#ffffff',
    fontSize: 11,
    fontWeight: 'bold',
  },
  wizardContinueBtn: {
    width: '100%',
    backgroundColor: '#6366f1',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
    marginTop: 10,
  },
  wizardContinueBtnDisabled: {
    backgroundColor: '#1e293b',
    opacity: 0.5,
  },
  wizardContinueBtnText: {
    color: '#ffffff',
    fontWeight: 'bold',
    fontSize: 13,
  },
});
