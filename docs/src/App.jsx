import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { DownloadSimple, ShieldCheck, Star, Users, GithubLogo, AndroidLogo, Television, DeviceMobile, DeviceTablet, AppWindow, ChatCircleText, PictureInPicture, SpeakerHigh, ShieldSlash, Lightning, QrCode, Code, WifiHigh, Bell, MonitorPlay, WifiNone, Download, Package, Camera, HardDrives, Vibrate, Eye, CheckCircle, CaretDown, CaretUp, File, Clock, Tag } from '@phosphor-icons/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

// --- Components ---

const getAssetPath = (path) => {
  const base = import.meta.env.BASE_URL;
  // Ensure we don't end up with strictly double slashes unless needed, 
  // though browsers handle it fine.
  return base + (path.startsWith('/') ? path.slice(1) : path);
};

const Navbar = () => (
  <nav className="fixed top-0 w-full z-50 glass border-b border-white/5">
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
      <div className="flex items-center justify-between h-16">
        <div className="flex items-center gap-2">
          <img src={getAssetPath("logo.svg")} alt="KCIKTV" className="w-8 h-8 rounded-full" />
          <span className="font-bold text-xl tracking-tight">KCIKTV</span>
        </div>
        <div className="hidden md:flex items-center space-x-8">
          <a href="#features" className="text-gray-300 hover:text-brand-500 transition-colors text-sm font-medium">Features</a>
          <a href="#screenshots" className="text-gray-300 hover:text-brand-500 transition-colors text-sm font-medium">Screenshots</a>
        </div>
        <a href="#download" className="bg-brand-500 hover:bg-brand-400 text-black px-4 py-2 rounded-lg font-semibold text-sm transition-all transform hover:scale-105 active:scale-95">
          Download
        </a>
      </div>
    </div>
  </nav>
);

const Hero = () => (
  <section className="relative pt-32 pb-20 overflow-hidden hero-gradient">
    <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex flex-col items-center text-center">
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6 }}
        className="mb-6"
      >
        <img src={getAssetPath("logo.svg")} alt="KCIKTV" className="w-24 h-24 rounded-full mx-auto mb-6 shadow-[0_0_50px_rgba(83,252,24,0.3)]" />
        <h1 className="text-5xl md:text-7xl font-extrabold tracking-tight mb-4">
          KCIK<span className="text-brand-500">TV</span>
        </h1>
        <p className="text-xl md:text-2xl text-gray-400 max-w-2xl mx-auto leading-relaxed">
          The ultimate open-source Kick client for Android TV and Mobile.
        </p>
      </motion.div>



      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.5, duration: 0.6 }}
        className="flex flex-col sm:flex-row gap-4 w-full justify-center"
      >
        <a href="#download" className="bg-brand-500 hover:bg-brand-400 text-black px-8 py-4 rounded-xl font-bold text-lg transition-all transform hover:scale-105 active:scale-95 flex items-center justify-center gap-2 shadow-lg shadow-brand-500/20">
          <AndroidLogo weight="bold" size={24} />
          Install on Android
        </a>
        <a href="https://github.com/xacnio/kcik-tv-app" target="_blank" rel="noreferrer" className="bg-white/10 hover:bg-white/20 text-white px-8 py-4 rounded-xl font-bold text-lg transition-all flex items-center justify-center gap-2 backdrop-blur-sm">
          <GithubLogo weight="bold" size={24} />
          View Source
        </a>
      </motion.div>
    </div>

    {/* Background Decor */}
    <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[800px] h-[800px] bg-brand-500/5 rounded-full blur-3xl -z-10 pointer-events-none"></div>
  </section>
);

const ScreenshotTab = ({ active, label, onClick, icon: Icon }) => (
  <button
    onClick={onClick}
    className={`flex items-center gap-2 px-6 py-3 rounded-full font-semibold transition-all ${active
      ? 'bg-brand-500 text-black shadow-lg shadow-brand-500/20'
      : 'bg-white/5 text-gray-400 hover:bg-white/10 hover:text-white'
      }`}
  >
    <Icon weight="bold" size={20} />
    {label}
  </button>
);

const Screenshots = () => {
  const [activeTab, setActiveTab] = useState('tv');
  const [lightbox, setLightbox] = useState(null);

  const screenshots = {
    tv: [
      { src: getAssetPath("screenshots/tv-chat.png"), caption: "Chat Overlay" },
      { src: getAssetPath("screenshots/tv-player.png"), caption: "Player" },
      { src: getAssetPath("screenshots/tv-home.png"), caption: "Home Screen" }
    ],
    mobile: [
      { src: getAssetPath("screenshots/mobile-feed.png"), caption: "Feed" },
      { src: getAssetPath("screenshots/mobile-player.png"), caption: "Player" },
      { src: getAssetPath("screenshots/mobile-settings.png"), caption: "Settings" },
      { src: getAssetPath("screenshots/mobile-category-view.png"), caption: "Category View" }
    ],
    tablet: [
      { src: getAssetPath("screenshots/tablet-home.png"), caption: "Home Screen" },
      { src: getAssetPath("screenshots/tablet-player.png"), caption: "Player" }
    ]
  };

  const widthClass = {
    tv: 'w-[500px] md:w-[600px]',
    mobile: 'w-[240px] md:w-[280px]',
    tablet: 'w-[500px] md:w-[600px]'
  };

  return (
    <section id="screenshots" className="py-20 bg-black/20">
      <div className="max-w-7xl mx-auto px-4 text-center">
        <div className="mb-12">
          <h2 className="text-3xl md:text-4xl font-bold mb-4">Experience KCIK<span className="text-brand-500">TV</span></h2>
          <p className="text-gray-400">Designed for every screen size with a native feel.</p>
        </div>

        <div className="flex justify-center gap-4 mb-12 flex-wrap">
          <ScreenshotTab
            label="TV Interface"
            icon={Television}
            active={activeTab === 'tv'}
            onClick={() => setActiveTab('tv')}
          />
          <ScreenshotTab
            label="Mobile"
            icon={DeviceMobile}
            active={activeTab === 'mobile'}
            onClick={() => setActiveTab('mobile')}
          />
          <ScreenshotTab
            label="Tablet"
            icon={DeviceTablet}
            active={activeTab === 'tablet'}
            onClick={() => setActiveTab('tablet')}
          />
        </div>

        <div className="overflow-x-auto pb-8 hide-scrollbar flex gap-6 px-4 snap-x snap-mandatory justify-start md:justify-center">
          <AnimatePresence mode="wait">
            {screenshots[activeTab].map((item, idx) => (
              <motion.div
                key={`${activeTab}-${idx}`}
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                exit={{ opacity: 0, scale: 0.95 }}
                transition={{ duration: 0.3, delay: idx * 0.05 }}
                className={`flex-shrink-0 snap-center rounded-xl overflow-hidden shadow-2xl border border-white/10 cursor-pointer group hover:border-brand-500/30 transition-colors ${widthClass[activeTab]}`}
                onClick={() => setLightbox(item.src)}
              >
                <div className="relative">
                  <img src={item.src} alt={item.caption} className="w-full h-auto" loading="lazy" />
                  <div className="absolute inset-0 bg-black/0 group-hover:bg-black/20 transition-colors flex items-center justify-center">
                    <div className="opacity-0 group-hover:opacity-100 transition-opacity bg-black/60 backdrop-blur-sm rounded-full p-3">
                      <Eye weight="bold" size={20} className="text-white" />
                    </div>
                  </div>
                </div>
                <div className="bg-dark-card/80 backdrop-blur-sm px-4 py-2.5 border-t border-white/5">
                  <span className="text-xs font-medium text-gray-400">{item.caption}</span>
                </div>
              </motion.div>
            ))}
          </AnimatePresence>
        </div>
      </div>

      {/* Lightbox Modal */}
      <AnimatePresence>
        {lightbox && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="fixed inset-0 z-[100] bg-black/90 backdrop-blur-md flex items-center justify-center p-4 cursor-pointer"
            onClick={() => setLightbox(null)}
          >
            <motion.img
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.9, opacity: 0 }}
              transition={{ duration: 0.25 }}
              src={lightbox}
              alt="Screenshot preview"
              className="max-w-full max-h-[90vh] object-contain rounded-xl shadow-2xl"
            />
            <button
              onClick={() => setLightbox(null)}
              className="absolute top-6 right-6 text-white/60 hover:text-white transition-colors bg-white/10 hover:bg-white/20 rounded-full p-2 backdrop-blur-sm"
            >
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="18" y1="6" x2="6" y2="18" />
                <line x1="6" y1="6" x2="18" y2="18" />
              </svg>
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </section>
  );
};

const FeatureItem = ({ title, desc }) => (
  <div className="flex items-start gap-4 group">
    <div className="w-2 h-2 rounded-full bg-brand-500 mt-2.5 flex-shrink-0 group-hover:shadow-[0_0_8px_rgba(83,252,24,0.6)] transition-shadow"></div>
    <div>
      <h3 className="text-base font-bold text-white mb-1">{title}</h3>
      <p className="text-gray-400 text-sm leading-relaxed">{desc}</p>
    </div>
  </div>
);

const Features = () => {
  const [platform, setPlatform] = useState('mobile');

  const mobileFeatures = [
    { title: "Ad-Free Streaming", desc: "Zero ads — no pre-rolls, mid-rolls, or banners. Just the content, completely uninterrupted." },
    { title: "Background Audio & Picture-in-Picture", desc: "Lock your phone, switch apps — the stream never stops. Audio keeps playing in the background and auto-PiP lets you multitask without missing a moment." },
    { title: "Fullscreen & Landscape", desc: "Rotate to landscape for a true fullscreen experience. Supports sensor-based auto-rotation." },
    { title: "Theatre Mode", desc: "Optimized for vertical 9:16 streams (Kick Go Live). Watch portrait-format broadcasts with the player filling the screen properly." },
    { title: "Live DVR / Rewind", desc: "Pause and rewind live streams. Missed something? Seek back and catch up, then return to live." },
    { title: "VOD & Clip Playback", desc: "Watch past broadcasts (VODs) and clips with progress tracking and resume-where-you-left-off." },
    { title: "Playback Speed Control", desc: "Speed up or slow down VODs and clips (0.5x to 2x) to watch at your preferred pace." },
    { title: "Dynamic Quality Switching", desc: "Quality changes seamlessly without stopping the stream — you'll never miss a moment. Auto-adapts to your network or set manual limits to save data." },
    { title: "Advanced Chat", desc: "Highlighted messages (own, mentions, mod, VIP), vibrate on mention, message animations, customizable text & emote sizes, reply threads, and timestamps." },
    { title: "Quick Emote Panel", desc: "Per-channel recent emotes, subscriber emotes, Global/Channel/Emoji tabs — remembers your most-used emotes for each channel." },
    { title: "Floating Emotes & Combos", desc: "Enable floating emote animations and emote combo counters for an interactive chat experience." },
    { title: "Chat Moderation Tools", desc: "Mod actions directly from chat — timeout, ban, unban users, and view mod logs." },
    { title: "Gift Shop & Loyalty Points", desc: "Send gifts to streamers, track loyalty points, and participate in channel predictions." },
    { title: "Custom Equalizer", desc: "6-band audio EQ with presets (Bass Boost, Treble, Vocal, etc.), virtualizer, and reverb effects." },
    { title: "Screenshot Capture", desc: "Take high-resolution screenshots of the video stream with a single tap. Saved directly to gallery." },
    { title: "Stream & Clip Feed", desc: "Vertical swipe feed like Instagram Reels and TikTok — scroll through live streams and clips to discover new content faster." },
    { title: "Channel Profiles", desc: "View streamer profiles, about sections, social links, recent VODs, clips, and follow/unfollow." },
    { title: "Search & Discovery", desc: "Search channels and categories with history. Filter streams by language and block categories." },
    { title: "Notification Controls", desc: "Control playback directly from the notification shade — play/pause, mute, and close." },
    { title: "In-App Browser", desc: "Open links with an integrated browser — SSL indicator, share, copy link, and open in external browser." },
    { title: "In-App Updates", desc: "Check for updates and install new versions directly within the app. Stable & Beta channels supported." },
    { title: "Multi-Language Support", desc: "Available in English, Turkish, German, French, Spanish, and Arabic — with system language detection." },
    { title: "Open Source", desc: "Fully transparent, auditable code on GitHub. Community-driven and free forever." },
  ];

  const tvFeatures = [
    { title: "Lean-back Experience", desc: "Purpose-built UI for Android TV with D-pad/remote navigation and large, readable text." },
    { title: "QR Code Login", desc: "Scan a QR code with your phone to log in instantly — no typing on your TV remote." },
    { title: "Live Chat Overlay", desc: "See chat messages overlaid on the stream while watching in fullscreen on your TV." },
    { title: "Channel Browsing", desc: "Browse live channels, categories, and followed streamers with a TV-optimized interface." },
    { title: "Low Latency Playback", desc: "Direct HLS with minimal buffering ensures you're always in sync with the chat." },
    { title: "Background Audio", desc: "Keep listening to a stream while navigating other parts of the app." },
    { title: "Ad-Free Experience", desc: "Zero ads, no interruptions — just the content you want to watch." },
    { title: "Open Source", desc: "Same transparent codebase. Inspect, contribute, or build your own version." },
  ];

  const mobileChecklist = [
    "Sign in with OTP, two-factor authentication, or Google login through a secure WebView",
    "Home screen with featured streams, categories, popular clips, and trending category streams",
    "Browse live streams with advanced filtering and sorting options",
    "Explore categories to find content you love",
    "Discover and watch clips from your favorite streamers",
    "View category details including live streams, clips, and hide/unhide categories",
    "Follow channels and categories, and resume watching where you left off",
    "Swipeable vertical feed for streams and clips — scroll through content like TikTok or Reels",
    "Full-featured video player with mute toggle, quality selection, and real-time stats overlay",
    "View and interact with pinned messages in chat",
    "See pinned gift highlights during live streams",
    "Participate in channel predictions and track outcomes",
    "Vote in live polls created by streamers",
    "Earn and spend channel loyalty points",
    "Send KICKS gifts to streamers you support",
    "Display subscriber, moderator, VIP, and custom badges in chat",
    "Respect slow mode chat restrictions automatically",
    "Participate in subscriber-only chat when subscribed",
    "Quick-access emote picker with Global, Channel, and Emoji tabs",
    "Clip memorable moments with a single tap",
    "Access moderation tools — timeout, ban, and unban users directly from chat",
    "Execute moderator slash commands for channel management",
    "Mention users in chat with auto-complete suggestions",
    "Tap usernames to view profiles, follow, or perform quick actions",
    "Press messages to reply, copy, or see other details",
    "Preview and manage links shared in chat",
    "Open links in a built-in browser without leaving the app",
    "Customize your profile color and toggle badge visibility",
    "Highlight your own messages, mentions, and mod/VIP messages in chat",
    "Animate incoming chat messages with selectable animation styles",
    "Adjust chat text size and emote size independently to your preference",
    "Blerp sound integration for streamers that support it",
    "Theatre mode optimized for vertical 9:16 portrait live streams",
  ];

  const features = platform === 'mobile' ? mobileFeatures : tvFeatures;

  return (
    <section id="features" className="py-20 relative">
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center mb-12">
          <h2 className="text-3xl md:text-4xl font-bold mb-4">Features</h2>
          <p className="text-gray-400 max-w-2xl mx-auto">Everything you need for the best Kick experience, tailored for each platform.</p>
        </div>

        <div className="flex justify-center gap-3 mb-12">
          <button
            onClick={() => setPlatform('mobile')}
            className={`flex items-center gap-2 px-6 py-3 rounded-full font-semibold transition-all ${platform === 'mobile'
              ? 'bg-brand-500 text-black shadow-lg shadow-brand-500/20'
              : 'bg-white/5 text-gray-400 hover:bg-white/10 hover:text-white'
              }`}
          >
            <DeviceMobile weight="bold" size={20} />
            Mobile
          </button>
          <button
            onClick={() => setPlatform('tv')}
            className={`flex items-center gap-2 px-6 py-3 rounded-full font-semibold transition-all ${platform === 'tv'
              ? 'bg-brand-500 text-black shadow-lg shadow-brand-500/20'
              : 'bg-white/5 text-gray-400 hover:bg-white/10 hover:text-white'
              }`}
          >
            <Television weight="bold" size={20} />
            TV
          </button>
        </div>

        <AnimatePresence mode="wait">
          <motion.div
            key={platform}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.25 }}
            className="grid grid-cols-1 md:grid-cols-2 gap-x-12 gap-y-6"
          >
            {features.map((f, idx) => (
              <FeatureItem key={idx} title={f.title} desc={f.desc} />
            ))}
          </motion.div>
        </AnimatePresence>

        {platform === 'mobile' && (
          <motion.div
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3, delay: 0.1 }}
            className="mt-14"
          >
            <h3 className="text-xl font-semibold mb-6 text-center text-white/90">What's Built In</h3>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-x-10 gap-y-3">
              {mobileChecklist.map((item, idx) => (
                <div key={idx} className="flex items-start gap-3">
                  <CheckCircle weight="fill" size={20} className="text-brand-500 shrink-0 mt-0.5" />
                  <span className="text-sm text-gray-300">{item}</span>
                </div>
              ))}
            </div>
          </motion.div>
        )}
      </div>
    </section>
  );
};

const Permissions = () => (
  <section className="py-20 bg-black/30 border-y border-white/5">
    <div className="max-w-4xl mx-auto px-4">
      <h2 className="text-2xl font-bold mb-8 text-center">App Permissions</h2>
      <p className="text-gray-400 text-center mb-8 text-sm">KCIKTV only requests the permissions it truly needs. Here's everything the app uses and why:</p>
      <div className="bg-dark-card rounded-2xl p-8 border border-white/5">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="flex items-start gap-4">
            <WifiHigh weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">INTERNET</h4>
              <p className="text-xs text-gray-400 mt-1">Required to stream video content and connect to Kick chat servers.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <WifiHigh weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">ACCESS NETWORK STATE</h4>
              <p className="text-xs text-gray-400 mt-1">Checks network connectivity to handle offline/online transitions gracefully.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <Bell weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">POST NOTIFICATIONS</h4>
              <p className="text-xs text-gray-400 mt-1">For stream playback controls in the notification shade (play/pause, mute).</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <MonitorPlay weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">FOREGROUND SERVICE</h4>
              <p className="text-xs text-gray-400 mt-1">Ensures playback continues reliably in background and audio-only mode.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <SpeakerHigh weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">FOREGROUND SERVICE (MEDIA)</h4>
              <p className="text-xs text-gray-400 mt-1">Specifically for media playback foreground service type on Android 14+.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <Lightning weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">WAKE LOCK</h4>
              <p className="text-xs text-gray-400 mt-1">Prevents the device from sleeping during active stream playback.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <Package weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">REQUEST INSTALL PACKAGES</h4>
              <p className="text-xs text-gray-400 mt-1">Allows in-app updates by installing downloaded APK files.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <Eye weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">QUERY ALL PACKAGES</h4>
              <p className="text-xs text-gray-400 mt-1">Used to detect installed browsers for opening external links properly.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <Camera weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">CAMERA</h4>
              <p className="text-xs text-gray-400 mt-1">Used for QR code scanning to quickly log in on TV devices.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <Vibrate weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">VIBRATE</h4>
              <p className="text-xs text-gray-400 mt-1">Haptic feedback for UI interactions and notifications.</p>
            </div>
          </div>
          <div className="flex items-start gap-4">
            <HardDrives weight="duotone" size={24} className="text-gray-400 flex-shrink-0 mt-0.5" />
            <div>
              <h4 className="font-semibold text-white">STORAGE (Legacy, Android 9-)</h4>
              <p className="text-xs text-gray-400 mt-1">Used on older Android versions to save app update APKs temporarily.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
);



const formatBytes = (bytes) => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
};

const formatDate = (dateStr) => {
  const d = new Date(dateStr);
  return d.toLocaleDateString('tr-TR', { day: 'numeric', month: 'long', year: 'numeric' });
};

const MarkdownBody = ({ children }) => (
  <ReactMarkdown
    remarkPlugins={[remarkGfm]}
    components={{
      a: ({ node, ...props }) => (
        <a {...props} target="_blank" rel="noreferrer" className="text-brand-500 hover:text-brand-400 underline underline-offset-2 transition-colors" />
      ),
      p: ({ node, ...props }) => <p {...props} className="text-gray-300 text-sm leading-relaxed mb-2 last:mb-0" />,
      h3: ({ node, ...props }) => <h3 {...props} className="text-white font-semibold text-base mt-4 mb-2" />,
      h2: ({ node, ...props }) => <h2 {...props} className="text-white font-bold text-lg mt-4 mb-2" />,
      ul: ({ node, ...props }) => <ul {...props} className="list-disc list-inside space-y-1 text-gray-300 text-sm ml-2" />,
      ol: ({ node, ...props }) => <ol {...props} className="list-decimal list-inside space-y-1 text-gray-300 text-sm ml-2" />,
      li: ({ node, ...props }) => <li {...props} className="text-gray-300 text-sm leading-relaxed" />,
      strong: ({ node, ...props }) => <strong {...props} className="text-white font-semibold" />,
      code: ({ node, inline, ...props }) =>
        inline
          ? <code {...props} className="bg-white/10 text-brand-400 px-1.5 py-0.5 rounded text-xs font-mono" />
          : <code {...props} className="block bg-black/40 text-gray-300 p-3 rounded-lg text-xs font-mono overflow-x-auto" />,
      hr: () => <hr className="border-white/10 my-4" />,
    }}
  >
    {children}
  </ReactMarkdown>
);

const PastVersionItem = ({ release }) => {
  const [open, setOpen] = useState(false);
  const apk = release.assets?.find(a => a.name.endsWith('.apk'));

  return (
    <div className="border border-white/5 rounded-xl overflow-hidden bg-dark-card/50 hover:border-white/10 transition-colors">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-5 py-4 text-left group"
      >
        <div className="flex items-center gap-3 min-w-0">
          <Tag weight="duotone" size={20} className="text-brand-500 flex-shrink-0" />
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="font-semibold text-white text-sm">{release.tag_name}</span>
              {release.prerelease && (
                <span className="text-[10px] font-bold uppercase tracking-wider bg-yellow-500/20 text-yellow-400 px-2 py-0.5 rounded-full">Beta</span>
              )}
            </div>
            <div className="flex items-center gap-3 mt-0.5 text-xs text-gray-500">
              <span className="flex items-center gap-1">
                <Clock size={12} /> {formatDate(release.published_at)}
              </span>
              {apk && (
                <span className="flex items-center gap-1">
                  <File size={12} /> {formatBytes(apk.size)}
                </span>
              )}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-3 flex-shrink-0">
          {apk && (
            <a
              href={apk.browser_download_url}
              onClick={(e) => e.stopPropagation()}
              className="hidden sm:flex items-center gap-1.5 bg-brand-500/10 hover:bg-brand-500/20 text-brand-400 px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors"
            >
              <DownloadSimple weight="bold" size={14} />
              APK
            </a>
          )}
          <motion.div animate={{ rotate: open ? 180 : 0 }} transition={{ duration: 0.2 }}>
            <CaretDown size={18} className="text-gray-500 group-hover:text-gray-300 transition-colors" />
          </motion.div>
        </div>
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.25, ease: 'easeInOut' }}
            className="overflow-hidden"
          >
            <div className="px-5 pb-4 border-t border-white/5 pt-4">
              {apk && (
                <div className="flex items-center gap-3 mb-4 bg-black/30 rounded-lg px-4 py-3 border border-white/5">
                  <AndroidLogo weight="duotone" size={20} className="text-brand-500 flex-shrink-0" />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs text-gray-400 font-mono truncate">{apk.name}</p>
                  </div>
                  <a
                    href={apk.browser_download_url}
                    className="bg-brand-500 hover:bg-brand-400 text-black px-4 py-1.5 rounded-lg text-xs font-bold transition-colors flex items-center gap-1.5 flex-shrink-0"
                  >
                    <DownloadSimple weight="bold" size={14} />
                    İndir
                  </a>
                </div>
              )}
              {release.body ? (
                <div className="prose-custom">
                  <MarkdownBody>{release.body}</MarkdownBody>
                </div>
              ) : (
                <p className="text-gray-500 text-sm italic">Açıklama bulunmuyor.</p>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
};

const DownloadSection = () => {
  const [releases, setReleases] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [showPast, setShowPast] = useState(false);

  useEffect(() => {
    fetch('https://api.github.com/repos/xacnio/kcik-tv-app/releases')
      .then(res => {
        if (!res.ok) throw new Error('GitHub API isteği başarısız oldu');
        return res.json();
      })
      .then(data => {
        setReleases(data);
        setLoading(false);
      })
      .catch(err => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  const latestRelease = releases[0];
  const pastReleases = releases.slice(1);
  const latestApk = latestRelease?.assets?.find(a => a.name.endsWith('.apk'));

  return (
    <section id="download" className="py-20 bg-gradient-to-t from-brand-950/50 to-transparent">
      <div className="max-w-4xl mx-auto px-4">
        {/* --- Latest Release Card --- */}
        <div className="bg-dark-card border border-brand-500/20 rounded-3xl p-8 md:p-12 relative overflow-hidden group text-center">
          <div className="absolute inset-0 bg-brand-500/5 group-hover:bg-brand-500/10 transition-colors"></div>
          <div className="relative z-10">
            <h2 className="text-4xl md:text-5xl font-black mb-4">Ready to upgrade your viewing?</h2>

            {loading ? (
              <div className="flex flex-col items-center gap-4 py-8">
                <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin"></div>
                <p className="text-gray-400 text-sm">Yükleniyor...</p>
              </div>
            ) : error ? (
              <div className="py-6">
                <p className="text-red-400 text-sm mb-4">{error}</p>
                <a href="https://github.com/xacnio/kcik-tv-app/releases/latest" target="_blank" rel="noreferrer" className="bg-brand-500 hover:bg-brand-400 text-black px-8 py-4 rounded-xl font-bold text-lg transition-all inline-flex items-center gap-3">
                  <GithubLogo weight="bold" size={24} />
                  GitHub'dan İndir
                </a>
              </div>
            ) : latestRelease ? (
              <>
                <p className="text-xl text-gray-400 mb-2">
                  <span className="text-brand-500 font-semibold">{latestRelease.name || latestRelease.tag_name}</span>
                  {latestRelease.prerelease && (
                    <span className="ml-2 text-xs font-bold uppercase tracking-wider bg-yellow-500/20 text-yellow-400 px-2 py-1 rounded-full align-middle">Beta</span>
                  )}
                </p>
                <p className="text-sm text-gray-500 mb-6 flex items-center justify-center gap-4">
                  <span className="flex items-center gap-1"><Clock size={14} /> {formatDate(latestRelease.published_at)}</span>
                  {latestApk && <span className="flex items-center gap-1"><File size={14} /> {formatBytes(latestApk.size)}</span>}
                </p>

                {/* Description (Markdown) */}
                {latestRelease.body && (
                  <div className="text-left bg-black/30 rounded-2xl p-6 border border-white/5 mb-8 max-h-[400px] overflow-y-auto hide-scrollbar">
                    <MarkdownBody>{latestRelease.body}</MarkdownBody>
                  </div>
                )}

                {/* APK Download */}
                {latestApk && (
                  <div className="flex flex-col items-center gap-3">
                    <a
                      href={latestApk.browser_download_url}
                      className="bg-brand-500 hover:bg-brand-400 text-black px-8 py-4 rounded-xl font-bold text-lg transition-all shadow-lg shadow-brand-500/20 flex items-center justify-center gap-3 transform hover:scale-105 active:scale-95"
                    >
                      <DownloadSimple weight="bold" size={24} />
                      <div>
                        <div className="text-xs uppercase tracking-wider opacity-70 font-semibold">APK İndir</div>
                        <div className="leading-none">{latestApk.name}</div>
                      </div>
                    </a>
                  </div>
                )}

                <p className="mt-6 text-xs text-gray-500">Android 7.0 ve üzeri gerektirir. TV, Mobil & Tablet ile uyumludur.</p>
              </>
            ) : null}
          </div>
        </div>

        {/* --- Past Versions --- */}
        {pastReleases.length > 0 && (
          <div className="mt-12">
            <button
              onClick={() => setShowPast(!showPast)}
              className="w-full flex items-center justify-center gap-2 text-gray-400 hover:text-white transition-colors mb-6 group"
            >
              <Clock weight="duotone" size={20} />
              <span className="font-semibold text-sm">Geçmiş Sürümler ({pastReleases.length})</span>
              <motion.div animate={{ rotate: showPast ? 180 : 0 }} transition={{ duration: 0.2 }}>
                <CaretDown size={16} className="group-hover:text-brand-500 transition-colors" />
              </motion.div>
            </button>

            <AnimatePresence>
              {showPast && (
                <motion.div
                  initial={{ height: 0, opacity: 0 }}
                  animate={{ height: 'auto', opacity: 1 }}
                  exit={{ height: 0, opacity: 0 }}
                  transition={{ duration: 0.3, ease: 'easeInOut' }}
                  className="overflow-hidden"
                >
                  <div className="space-y-3">
                    {pastReleases.map((release) => (
                      <PastVersionItem key={release.id} release={release} />
                    ))}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        )}
      </div>
    </section>
  );
};

const Footer = () => (
  <footer className="border-t border-white/5 py-12 bg-black/20">
    <div className="max-w-7xl mx-auto px-4 flex flex-col md:flex-row justify-between items-center gap-6">
      <div className="flex items-center gap-2">
        <img src={getAssetPath("logo.svg")} alt="KCIKTV" className="w-6 h-6 rounded-full" />
        <span className="font-semibold text-gray-300">KCIKTV</span>
      </div>

      <div className="flex gap-6 text-sm text-gray-400">
        <a href="#" className="hover:text-brand-500 transition-colors">Home</a>
        <a href="privacy_policy.md" className="hover:text-brand-500 transition-colors">Privacy Policy</a>
        <a href="https://github.com/xacnio/kcik-tv-app/issues" className="hover:text-brand-500 transition-colors">Report Issue</a>
      </div>

      <div className="text-xs text-gray-600">
        &copy; 2026 KCIKTV. Open Source Project. Not affiliated with Kick.com.
      </div>
    </div>
  </footer>
);

function App() {
  return (
    <div className="min-h-screen bg-dark-bg text-white selection:bg-brand-500 selection:text-black">
      <Navbar />
      <Hero />
      <Features />
      <Screenshots />
      <Permissions />
      <DownloadSection />
      <Footer />
    </div>
  );
}

export default App;
