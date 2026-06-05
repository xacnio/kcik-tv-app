import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  DownloadSimple, ShieldCheck, ShieldSlash, GithubLogo, AndroidLogo,
  Television, DeviceMobile, DeviceTablet, AppWindow, ChatCircleText,
  PlayCircle, Medal, Gift, Compass, UserCircle, Gauge,
  Code, Translate, Check, Eye, CaretDown, File, Clock, Tag,
  List, X, SignIn, GearSix, Coffee
} from '@phosphor-icons/react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import FeatureMock from './FeatureMocks';

// --- Helpers ---

const getAssetPath = (path) => {
  const base = import.meta.env.BASE_URL;
  return base + (path.startsWith('/') ? path.slice(1) : path);
};

const GITHUB_REPO = 'https://github.com/xacnio/kcik-tv-app';

const SectionHeading = ({ eyebrow, title, subtitle }) => (
  <div className="mb-12 max-w-2xl">
    {eyebrow && (
      <span className="mb-3 block font-mono text-xs uppercase tracking-[0.2em] text-brand-500">
        {eyebrow}
      </span>
    )}
    <h2 className="text-3xl font-semibold tracking-tight md:text-[2.5rem] md:leading-[1.1]">{title}</h2>
    {subtitle && <p className="mt-3 text-[15px] leading-relaxed text-gray-400">{subtitle}</p>}
  </div>
);

// --- Navbar ---

const NAV_LINKS = [
  { href: '#features', label: 'Features' },
  { href: '#screenshots', label: 'Screenshots' },
  { href: '#download', label: 'Download' },
];

const Navbar = () => {
  const [open, setOpen] = useState(false);

  return (
    <nav className="fixed top-0 z-50 w-full glass">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6 lg:px-8">
        <a href="#top" onClick={() => setOpen(false)} className="flex items-center gap-2.5">
          <img src={getAssetPath('logo.svg')} alt="KCIKTV" className="h-8 w-8 rounded-full" />
          <span className="text-xl font-extrabold tracking-tight">KCIKTV</span>
        </a>

        <div className="hidden items-center gap-8 md:flex">
          {NAV_LINKS.map((l) => (
            <a key={l.href} href={l.href} className="text-sm font-medium text-gray-300 transition-colors hover:text-brand-500">
              {l.label}
            </a>
          ))}
        </div>

        <div className="flex items-center gap-2">
          <a
            href={GITHUB_REPO}
            target="_blank"
            rel="noreferrer"
            aria-label="GitHub"
            className="hidden h-10 w-10 items-center justify-center rounded-lg text-gray-300 transition-colors hover:bg-white/5 hover:text-white sm:flex"
          >
            <GithubLogo weight="fill" size={22} />
          </a>
          <a
            href="#download"
            className="hidden rounded-lg bg-brand-500 px-4 py-2 text-sm font-semibold text-black transition-all hover:bg-brand-400 active:scale-95 sm:block"
          >
            Download
          </a>
          <button
            onClick={() => setOpen(!open)}
            aria-label="Toggle menu"
            className="flex h-10 w-10 items-center justify-center rounded-lg text-gray-300 transition-colors hover:bg-white/5 md:hidden"
          >
            {open ? <X size={22} /> : <List size={22} />}
          </button>
        </div>
      </div>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="overflow-hidden border-t border-white/[0.06] md:hidden"
          >
            <div className="flex flex-col gap-1 px-4 py-3">
              {NAV_LINKS.map((l) => (
                <a
                  key={l.href}
                  href={l.href}
                  onClick={() => setOpen(false)}
                  className="rounded-lg px-3 py-2.5 text-sm font-medium text-gray-300 transition-colors hover:bg-white/5 hover:text-brand-500"
                >
                  {l.label}
                </a>
              ))}
              <a
                href={GITHUB_REPO}
                target="_blank"
                rel="noreferrer"
                onClick={() => setOpen(false)}
                className="rounded-lg px-3 py-2.5 text-sm font-medium text-gray-300 transition-colors hover:bg-white/5 hover:text-brand-500"
              >
                GitHub
              </a>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </nav>
  );
};

// --- Hero ---

const heroBadges = [
  { icon: ShieldSlash, label: 'Zero Ads' },
  { icon: ShieldCheck, label: 'No Tracking' },
  { icon: Code, label: 'Open Source' },
  { icon: Translate, label: '6 Languages' },
];

const HeroShowcase = () => (
  <motion.div
    initial={{ opacity: 0, y: 40 }}
    animate={{ opacity: 1, y: 0 }}
    transition={{ delay: 0.7, duration: 0.8, ease: 'easeOut' }}
    className="relative mx-auto mt-20 w-full max-w-4xl px-4"
  >
    {/* TV / desktop frame */}
    <div className="relative overflow-hidden rounded-xl border border-white/10 bg-dark-card shadow-xl shadow-black/40">
      <div className="flex items-center gap-1.5 border-b border-white/10 bg-black/30 px-4 py-2.5">
        <span className="h-2.5 w-2.5 rounded-full bg-white/15" />
        <span className="h-2.5 w-2.5 rounded-full bg-white/15" />
        <span className="h-2.5 w-2.5 rounded-full bg-white/15" />
      </div>
      <img src={getAssetPath('screenshots/tv-home.png')} alt="KCIKTV on Android TV" className="w-full" loading="eager" />
    </div>

    {/* Floating phone */}
    <motion.div
      initial={{ opacity: 0, x: 20, y: 24 }}
      animate={{ opacity: 1, x: 0, y: 0 }}
      transition={{ delay: 1, duration: 0.7, ease: 'easeOut' }}
      className="absolute -bottom-8 right-2 hidden w-[150px] overflow-hidden rounded-[1.75rem] border-4 border-dark-border bg-dark-card shadow-xl shadow-black/50 md:block lg:right-6 lg:w-[180px]"
    >
      <img src={getAssetPath('screenshots/mobile-feed.png')} alt="KCIKTV on mobile" className="w-full" loading="eager" />
    </motion.div>
  </motion.div>
);

const Hero = () => (
  <section id="top" className="relative overflow-hidden pb-28 pt-36">
    <div className="mx-auto flex max-w-3xl flex-col items-center px-4 text-center sm:px-6">
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.6, ease: 'easeOut' }}
      >
        <img
          src={getAssetPath('logo.svg')}
          alt="KCIKTV"
          className="mx-auto mb-8 h-20 w-20 rounded-2xl border border-white/10"
        />
      </motion.div>

      <motion.span
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.15, duration: 0.5 }}
        className="mb-5 block font-mono text-xs uppercase tracking-[0.2em] text-gray-500"
      >
        Unofficial Kick client · TV · Mobile · Tablet
      </motion.span>

      <motion.h1
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.25, duration: 0.6 }}
        className="text-5xl font-bold tracking-tight md:text-7xl"
      >
        KCIK<span className="text-brand-500">TV</span>
      </motion.h1>

      <motion.p
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.35, duration: 0.6 }}
        className="mx-auto mt-5 max-w-xl text-lg leading-relaxed text-gray-400"
      >
        An open-source Kick client for Android. No ads, low latency, and no tracking.
      </motion.p>

      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ delay: 0.45, duration: 0.6 }}
        className="mt-9 flex w-full flex-col justify-center gap-3 sm:flex-row"
      >
        <a
          href="#download"
          className="flex items-center justify-center gap-2 rounded-md bg-brand-500 px-7 py-3 text-base font-semibold text-black transition-colors hover:bg-brand-400"
        >
          <AndroidLogo weight="bold" size={20} />
          Install on Android
        </a>
        <a
          href={GITHUB_REPO}
          target="_blank"
          rel="noreferrer"
          className="flex items-center justify-center gap-2 rounded-md border border-white/15 px-7 py-3 text-base font-semibold text-white transition-colors hover:bg-white/5"
        >
          <GithubLogo weight="bold" size={20} />
          View Source
        </a>
      </motion.div>

      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ delay: 0.6, duration: 0.6 }}
        className="mt-10 flex flex-wrap items-center justify-center gap-x-6 gap-y-3"
      >
        {heroBadges.map(({ icon: Icon, label }) => (
          <span key={label} className="flex items-center gap-2 text-sm text-gray-400">
            <Icon weight="regular" size={18} className="text-brand-500" />
            {label}
          </span>
        ))}
      </motion.div>
    </div>

    <HeroShowcase />
  </section>
);

// --- Platforms strip ---

const platforms = [
  { icon: DeviceMobile, label: 'Phone' },
  { icon: Television, label: 'Android TV' },
  { icon: DeviceTablet, label: 'Tablet' },
];

const Platforms = () => (
  <section className="border-y border-white/[0.06] bg-black/20 py-10">
    <div className="mx-auto max-w-5xl px-4">
      <div className="flex flex-col items-center gap-6 md:flex-row md:justify-between">
        <p className="font-mono text-xs uppercase tracking-[0.2em] text-gray-500">
          Supported devices
        </p>
        <div className="flex flex-wrap items-center justify-center gap-8">
          {platforms.map(({ icon: Icon, label }) => (
            <div key={label} className="flex items-center gap-2.5 text-gray-300">
              <Icon weight="regular" size={24} className="text-brand-500" />
              <span className="text-sm font-semibold">{label}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  </section>
);

// --- Features (hover-reveal split directory) ---

const featureCategories = [
  {
    label: 'Playback & player',
    icon: PlayCircle,
    desc: 'A full-featured player tuned for low-latency live streams.',
    shot: 'screenshots/mobile-player.png',
    items: [
      { name: 'Completely ad-free' },
      { name: 'Low-latency playback (Amazon IVS)' },
      { name: 'Adaptive quality + data limit' },
      { name: 'Live DVR / rewind' },
      { name: 'VOD & clip playback' },
      { name: 'Playback speed 0.5–2×' },
      { name: 'Theatre mode (vertical)' },
      { name: 'Fullscreen, rotate & gestures' },
      { name: 'Picture-in-Picture (auto)' },
      { name: 'Background audio' },
      { name: 'Mini player (drag to shrink)' },
      { name: 'One-tap screenshot capture' },
      { name: '6-band custom equalizer' },
      { name: 'Nerd-stats overlay' },
      { name: 'Notification media controls' },
    ],
  },
  {
    label: 'Chat',
    icon: ChatCircleText,
    desc: 'A native chat client with everything Kick chat can do, and more.',
    shot: 'screenshots/tv-chat.png',
    mock: 'chat',
    items: [
      { name: 'Reply threads' },
      { name: 'Mentions + autocomplete' },
      { name: 'Vibrate, sound & push on mention', new: true },
      { name: 'Highlights: own, mod, VIP & OG', new: true },
      { name: 'Fill-background highlight mode', new: true },
      { name: 'Message entry animations' },
      { name: 'Adjustable text & emote size' },
      { name: 'Timestamps' },
      { name: 'Floating emotes & combos' },
      { name: 'Quick emote bar (recents)' },
      { name: 'Emote panel: Global / Channel / Emoji' },
      { name: 'Pinned messages' },
      { name: 'Pinned gift banners', new: true },
      { name: 'Celebration banners', new: true },
      { name: 'Background buffering + load-missed', new: true },
      { name: 'Nick detection (alerts without @)', new: true },
      { name: 'Slow / sub-only / followers-only' },
    ],
  },
  {
    label: 'Identity & levels',
    icon: Medal,
    desc: 'Show off your account level, XP and badges across the app.',
    shot: 'screenshots/mobile-player.png',
    mock: 'identity',
    items: [
      { name: 'Account levels & level badge', new: true },
      { name: 'Animated XP progress', new: true },
      { name: 'badges_v2 support', new: true },
      { name: 'Global vs channel badge split', new: true },
      { name: 'Pick up to 4 visible badges', new: true },
      { name: 'Custom profile color' },
      { name: 'APNG animated badges' },
    ],
  },
  {
    label: 'Moderation',
    icon: ShieldCheck,
    desc: 'Run your channel without leaving the stream.',
    shot: 'screenshots/mobile-player.png',
    items: [
      { name: 'Ban, timeout & unban' },
      { name: 'Pin / unpin messages' },
      { name: 'Edit stream info (title & category)' },
      { name: 'Channel chat settings' },
      { name: 'Reward request queue', new: true },
      { name: 'Mod emote channels', new: true },
    ],
  },
  {
    label: 'Engagement',
    icon: Gift,
    desc: 'Take part in everything happening on stream.',
    shot: 'screenshots/mobile-player.png',
    items: [
      { name: 'Predictions (vote, presets, balance)' },
      { name: 'Polls (vote & live results)' },
      { name: 'Gift shop — KICKS, basic & level-up' },
      { name: 'Loyalty points & rewards' },
      { name: 'Gifted subs' },
    ],
  },
  {
    label: 'Discovery',
    icon: Compass,
    desc: 'Find streams and clips faster than the official app.',
    shot: 'screenshots/mobile-feed.png',
    items: [
      { name: 'Interactive featured hero', new: true },
      { name: 'Recently-watched row', new: true },
      { name: 'Warm-start Following tab', new: true },
      { name: 'Top categories & popular clips' },
      { name: 'Search with history' },
      { name: 'Browse with filter & sort' },
      { name: 'Vertical feed (streams + clips)' },
      { name: 'Language filter & hidden categories' },
      { name: 'Channel profiles, VODs & socials' },
    ],
  },
  {
    label: 'Account & access',
    icon: UserCircle,
    desc: 'Sign in your way and switch accounts instantly.',
    shot: 'screenshots/mobile-settings.png',
    items: [
      { name: 'Multi-account + switcher', new: true },
      { name: 'Seamless switch (no chat reset)', new: true },
      { name: 'QR code login (TV)' },
      { name: 'OTP, 2FA & Google login' },
      { name: 'TV ⇄ Mobile UI switch' },
      { name: 'Follow channels & categories' },
    ],
  },
  {
    label: 'Performance & more',
    icon: Gauge,
    desc: 'Light on resources, with the extras that round it out.',
    shot: 'screenshots/mobile-settings.png',
    mock: 'settings',
    items: [
      { name: 'Battery Saver mode', new: true },
      { name: 'Per-surface battery controls', new: true },
      { name: 'Thermal / adaptive performance', new: true },
      { name: 'In-app updates (stable & beta)' },
      { name: '6 languages + system detection' },
      { name: 'Link previews + trusted domains' },
      { name: 'Built-in browser' },
      { name: 'Open source, no ad tracking' },
    ],
  },
];

const NewTag = () => (
  <span className="rounded bg-brand-500/15 px-1.5 py-0.5 font-mono text-[10px] uppercase tracking-wider text-brand-500">
    New
  </span>
);

const Features = () => {
  const [active, setActive] = useState(0);
  const cat = featureCategories[active];

  return (
    <section id="features" className="scroll-mt-20 py-24">
      <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
        <SectionHeading
          eyebrow="Features"
          title="Everything packed in"
          subtitle="Dozens of features across eight areas. The green New tag marks what the latest betas added."
        />

        <div className="grid grid-cols-1 gap-8 md:grid-cols-[minmax(220px,260px)_1fr] md:gap-12">
          {/* Category rail */}
          <div className="flex flex-col">
            {featureCategories.map((c, i) => {
              const Icon = c.icon;
              const isActive = i === active;
              return (
                <button
                  key={c.label}
                  onMouseEnter={() => setActive(i)}
                  onClick={() => setActive(i)}
                  className={`flex items-center gap-3 border-l-2 px-4 py-3 text-left transition-colors ${isActive
                    ? 'border-brand-500 bg-white/[0.03] text-white'
                    : 'border-white/10 text-gray-400 hover:text-white'
                    }`}
                >
                  <Icon weight="regular" size={20} className={isActive ? 'text-brand-500' : 'text-gray-500'} />
                  <span className="flex-1 text-sm font-medium">{c.label}</span>
                  <span className="font-mono text-xs text-gray-600">{c.items.length}</span>
                </button>
              );
            })}
          </div>

          {/* Active category panel */}
          <AnimatePresence mode="wait">
            <motion.div
              key={active}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -8 }}
              transition={{ duration: 0.25, ease: 'easeOut' }}
              className="lg:grid lg:grid-cols-[1fr_230px] lg:gap-10"
            >
              <div>
                <h3 className="font-mono text-xs uppercase tracking-[0.2em] text-brand-500">{cat.label}</h3>
                <p className="mt-2 max-w-md text-[15px] leading-relaxed text-gray-400">{cat.desc}</p>

                <div className="mt-6 grid grid-cols-1 gap-x-8 gap-y-3 border-t border-white/10 pt-6 sm:grid-cols-2">
                  {cat.items.map((item) => (
                    <div key={item.name} className="flex items-center gap-2.5 text-sm text-gray-300">
                      <Check weight="bold" size={14} className="shrink-0 text-brand-500" />
                      <span>{item.name}</span>
                      {item.new && <NewTag />}
                    </div>
                  ))}
                </div>
              </div>

              <div className="mt-8 hidden justify-center lg:mt-0 lg:flex">
                <FeatureMock mock={cat.mock} shotSrc={getAssetPath(cat.shot)} />
              </div>
            </motion.div>
          </AnimatePresence>
        </div>
      </div>
    </section>
  );
};

// --- Screenshots ---

const ScreenshotTab = ({ active, label, onClick, icon: Icon }) => (
  <button
    onClick={onClick}
    className={`flex items-center gap-2 rounded-full px-5 py-2.5 text-sm font-semibold transition-all ${active
      ? 'bg-brand-500 text-black shadow-lg shadow-brand-500/20'
      : 'bg-white/5 text-gray-400 hover:bg-white/10 hover:text-white'
      }`}
  >
    <Icon weight="bold" size={18} />
    {label}
  </button>
);

const Screenshots = () => {
  const [activeTab, setActiveTab] = useState('tv');
  const [lightbox, setLightbox] = useState(null);

  const screenshots = {
    tv: [
      { src: getAssetPath('screenshots/tv-chat.png'), caption: 'Chat Overlay' },
      { src: getAssetPath('screenshots/tv-player.png'), caption: 'Player' },
      { src: getAssetPath('screenshots/tv-home.png'), caption: 'Home Screen' },
    ],
    mobile: [
      { src: getAssetPath('screenshots/mobile-feed.png'), caption: 'Feed' },
      { src: getAssetPath('screenshots/mobile-player.png'), caption: 'Player' },
      { src: getAssetPath('screenshots/mobile-settings.png'), caption: 'Settings' },
      { src: getAssetPath('screenshots/mobile-category-view.png'), caption: 'Category View' },
    ],
    tablet: [
      { src: getAssetPath('screenshots/tablet-home.png'), caption: 'Home Screen' },
      { src: getAssetPath('screenshots/tablet-player.png'), caption: 'Player' },
    ],
  };

  const widthClass = {
    tv: 'w-full sm:w-[440px] lg:w-[500px]',
    mobile: 'w-[150px] sm:w-[185px] lg:w-[215px]',
    tablet: 'w-full sm:w-[440px] lg:w-[500px]',
  };

  return (
    <section id="screenshots" className="scroll-mt-20 border-y border-white/[0.06] bg-black/20 py-24">
      <div className="mx-auto max-w-6xl px-4">
        <SectionHeading
          eyebrow="Screenshots"
          title="How it looks"
          subtitle="The same app on your TV, your phone, and tablets."
        />

        <div className="mb-12 flex flex-wrap justify-start gap-3">
          <ScreenshotTab label="TV" icon={Television} active={activeTab === 'tv'} onClick={() => setActiveTab('tv')} />
          <ScreenshotTab label="Mobile" icon={DeviceMobile} active={activeTab === 'mobile'} onClick={() => setActiveTab('mobile')} />
          <ScreenshotTab label="Tablet" icon={DeviceTablet} active={activeTab === 'tablet'} onClick={() => setActiveTab('tablet')} />
        </div>

        <div className="flex flex-wrap items-start justify-start gap-5 pb-2 pt-2 sm:gap-6">
          <AnimatePresence mode="wait">
            {screenshots[activeTab].map((item, idx) => {
              const isMobile = activeTab === 'mobile';
              return (
                <motion.button
                  key={`${activeTab}-${idx}`}
                  initial={{ opacity: 0, scale: 0.95 }}
                  animate={{ opacity: 1, scale: 1 }}
                  exit={{ opacity: 0, scale: 0.95 }}
                  transition={{ duration: 0.3, delay: idx * 0.05 }}
                  onClick={() => setLightbox(item.src)}
                  className={`group relative text-left transition-transform duration-300 hover:-translate-y-1 ${widthClass[activeTab]}`}
                >
                  <div
                    className={`relative overflow-hidden bg-dark-card shadow-md shadow-black/30 transition-all duration-300 ${isMobile
                      ? 'rounded-[1.75rem] ring-[5px] ring-dark-border group-hover:ring-white/25'
                      : 'rounded-xl ring-1 ring-white/10 group-hover:ring-white/25'
                      }`}
                  >
                    {!isMobile && (
                      <div className="flex items-center gap-1.5 border-b border-white/10 bg-black/40 px-3 py-2">
                        <span className="h-2 w-2 rounded-full bg-white/15" />
                        <span className="h-2 w-2 rounded-full bg-white/15" />
                        <span className="h-2 w-2 rounded-full bg-white/15" />
                      </div>
                    )}
                    <img src={item.src} alt={item.caption} className="w-full" loading="lazy" />
                    <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 bg-gradient-to-t from-black/85 via-black/20 to-transparent px-4 pb-3 pt-12">
                      <span className="text-sm font-semibold text-white">{item.caption}</span>
                      <span className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full bg-white/15 text-white opacity-0 backdrop-blur-sm transition-opacity duration-300 group-hover:opacity-100">
                        <Eye weight="bold" size={14} />
                      </span>
                    </div>
                  </div>
                </motion.button>
              );
            })}
          </AnimatePresence>
        </div>
        <p className="mt-3 text-xs text-gray-500">Click any screenshot to view it full size.</p>
      </div>

      {/* Lightbox */}
      <AnimatePresence>
        {lightbox && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            className="fixed inset-0 z-[100] flex cursor-pointer items-center justify-center bg-black/90 p-4 backdrop-blur-md"
            onClick={() => setLightbox(null)}
          >
            <motion.img
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.9, opacity: 0 }}
              transition={{ duration: 0.25 }}
              src={lightbox}
              alt="Screenshot preview"
              className="max-h-[90vh] max-w-full rounded-xl object-contain shadow-2xl"
            />
            <button
              onClick={() => setLightbox(null)}
              aria-label="Close"
              className="absolute right-6 top-6 rounded-full bg-white/10 p-2 text-white/60 backdrop-blur-sm transition-colors hover:bg-white/20 hover:text-white"
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

// --- Permissions ---

const permissions = [
  { name: 'INTERNET', desc: 'Stream video and connect to Kick chat servers.' },
  { name: 'ACCESS NETWORK STATE', desc: 'Detect when you go offline or come back online.' },
  { name: 'POST NOTIFICATIONS', desc: 'Playback controls in the notification shade.' },
  { name: 'FOREGROUND SERVICE', desc: 'Keep playback running in the background.' },
  { name: 'FOREGROUND SERVICE (MEDIA)', desc: 'Media playback service type on Android 14+.' },
  { name: 'WAKE LOCK', desc: 'Prevent the device from sleeping during playback.' },
  { name: 'REQUEST INSTALL PACKAGES', desc: 'Install downloaded in-app update APKs.' },
  { name: 'QUERY ALL PACKAGES', desc: 'Detect installed browsers for external links.' },
  { name: 'CAMERA', desc: 'Scan QR codes to log in on TV.' },
  { name: 'VIBRATE', desc: 'Haptic feedback for interactions and mentions.' },
  { name: 'STORAGE (Android 9 and older)', desc: 'Temporarily save update APKs on older devices.' },
];

const Permissions = () => (
  <section className="py-24">
    <div className="mx-auto max-w-4xl px-4">
      <SectionHeading
        eyebrow="Permissions"
        title="What the app can access"
        subtitle="Every Android permission KCIKTV requests, and why it needs it."
      />
      <dl className="mt-2 grid grid-cols-1 gap-x-10 gap-y-5 sm:grid-cols-2 lg:grid-cols-3">
        {permissions.map(({ name, desc }) => (
          <div key={name}>
            <dt className="font-mono text-[11px] uppercase tracking-wider text-white/90">{name}</dt>
            <dd className="mt-1 text-xs leading-relaxed text-gray-500">{desc}</dd>
          </div>
        ))}
      </dl>
    </div>
  </section>
);

// --- Get started ---

const steps = [
  { icon: DownloadSimple, title: 'Download the APK', desc: 'Get the latest release from GitHub. No store account needed.' },
  { icon: GearSix, title: 'Allow the install', desc: 'When prompted, enable “Install unknown apps” for your browser or files app.' },
  { icon: SignIn, title: 'Open and sign in', desc: 'Open KCIKTV, log in (or scan the QR code on TV), and start watching.' },
];

const GetStarted = () => (
  <section className="border-y border-white/10 bg-black/20 py-24">
    <div className="mx-auto max-w-5xl px-4">
      <SectionHeading
        eyebrow="Install"
        title="How to install"
        subtitle="It's a normal APK. Download it from GitHub and install it like any other app."
      />
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {steps.map(({ icon: Icon, title, desc }, i) => (
          <div key={title} className="bento h-full p-7">
            <div className="mb-5 flex items-center justify-between">
              <Icon weight="regular" size={24} className="text-brand-500" />
              <span className="font-mono text-3xl font-semibold leading-none text-white/15">{String(i + 1).padStart(2, '0')}</span>
            </div>
            <h3 className="text-lg font-semibold">{title}</h3>
            <p className="mt-2 text-sm leading-relaxed text-gray-400">{desc}</p>
          </div>
        ))}
      </div>
    </div>
  </section>
);

// --- Releases / Download ---

const formatBytes = (bytes) => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
};

const formatDate = (dateStr) => {
  const d = new Date(dateStr);
  return d.toLocaleDateString('en-US', { day: 'numeric', month: 'long', year: 'numeric' });
};

const MarkdownBody = ({ children }) => (
  <ReactMarkdown
    remarkPlugins={[remarkGfm]}
    rehypePlugins={[rehypeRaw]}
    components={{
      a: ({ node, ...props }) => (
        <a {...props} target="_blank" rel="noreferrer" className="text-brand-500 underline underline-offset-2 transition-colors hover:text-brand-400" />
      ),
      img: ({ node, ...props }) => (
        <img {...props} loading="lazy" className="my-3 h-auto max-w-full rounded-lg border border-white/10" />
      ),
      p: ({ node, ...props }) => <p {...props} className="mb-2 text-sm leading-relaxed text-gray-300 last:mb-0" />,
      h3: ({ node, ...props }) => <h3 {...props} className="mb-2 mt-4 text-base font-semibold text-white" />,
      h2: ({ node, ...props }) => <h2 {...props} className="mb-2 mt-4 text-lg font-bold text-white" />,
      ul: ({ node, ...props }) => <ul {...props} className="ml-2 list-inside list-disc space-y-1 text-sm text-gray-300" />,
      ol: ({ node, ...props }) => <ol {...props} className="ml-2 list-inside list-decimal space-y-1 text-sm text-gray-300" />,
      li: ({ node, ...props }) => <li {...props} className="text-sm leading-relaxed text-gray-300" />,
      strong: ({ node, ...props }) => <strong {...props} className="font-semibold text-white" />,
      code: ({ node, inline, ...props }) =>
        inline
          ? <code {...props} className="rounded bg-white/10 px-1.5 py-0.5 font-mono text-xs text-brand-400" />
          : <code {...props} className="block overflow-x-auto rounded-lg bg-black/40 p-3 font-mono text-xs text-gray-300" />,
      hr: () => <hr className="my-4 border-white/10" />,
    }}
  >
    {children}
  </ReactMarkdown>
);

const PastVersionItem = ({ release }) => {
  const [open, setOpen] = useState(false);
  const apk = release.assets?.find((a) => a.name.endsWith('.apk'));

  return (
    <div className="overflow-hidden rounded-lg border border-white/10 bg-dark-card transition-colors hover:border-white/20">
      <button onClick={() => setOpen(!open)} className="group flex w-full items-center justify-between px-5 py-4 text-left">
        <div className="flex min-w-0 items-center gap-3">
          <Tag weight="regular" size={18} className="flex-shrink-0 text-brand-500" />
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-semibold text-white">{release.tag_name}</span>
              {release.prerelease && (
                <span className="rounded-full bg-yellow-500/20 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-yellow-400">Beta</span>
              )}
            </div>
            <div className="mt-0.5 flex items-center gap-3 text-xs text-gray-500">
              <span className="flex items-center gap-1"><Clock size={12} /> {formatDate(release.published_at)}</span>
              {apk && <span className="flex items-center gap-1"><File size={12} /> {formatBytes(apk.size)}</span>}
            </div>
          </div>
        </div>
        <div className="flex flex-shrink-0 items-center gap-3">
          {apk && (
            <a
              href={apk.browser_download_url}
              onClick={(e) => e.stopPropagation()}
              className="hidden items-center gap-1.5 rounded-lg bg-brand-500/10 px-3 py-1.5 text-xs font-semibold text-brand-400 transition-colors hover:bg-brand-500/20 sm:flex"
            >
              <DownloadSimple weight="bold" size={14} /> APK
            </a>
          )}
          <motion.div animate={{ rotate: open ? 180 : 0 }} transition={{ duration: 0.2 }}>
            <CaretDown size={18} className="text-gray-500 transition-colors group-hover:text-gray-300" />
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
            <div className="border-t border-white/5 px-5 pb-4 pt-4">
              {apk && (
                <div className="mb-4 flex items-center gap-3 rounded-lg border border-white/5 bg-black/30 px-4 py-3">
                  <AndroidLogo weight="regular" size={20} className="flex-shrink-0 text-brand-500" />
                  <p className="min-w-0 flex-1 truncate font-mono text-xs text-gray-400">{apk.name}</p>
                  <a
                    href={apk.browser_download_url}
                    className="flex flex-shrink-0 items-center gap-1.5 rounded-lg bg-brand-500 px-4 py-1.5 text-xs font-bold text-black transition-colors hover:bg-brand-400"
                  >
                    <DownloadSimple weight="bold" size={14} /> Download
                  </a>
                </div>
              )}
              {release.body ? (
                <MarkdownBody>{release.body}</MarkdownBody>
              ) : (
                <p className="text-sm italic text-gray-500">No release notes.</p>
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
      .then((res) => {
        if (!res.ok) throw new Error('Could not reach GitHub. Please try again later.');
        return res.json();
      })
      .then((data) => {
        const sorted = data.sort((a, b) => new Date(b.published_at) - new Date(a.published_at));
        setReleases(sorted);
        setLoading(false);
      })
      .catch((err) => {
        setError(err.message);
        setLoading(false);
      });
  }, []);

  const latestRelease = releases[0];
  const pastReleases = releases.slice(1);
  const latestApk = latestRelease?.assets?.find((a) => a.name.endsWith('.apk'));

  return (
    <section id="download" className="scroll-mt-20 border-t border-white/10 py-24">
      <div className="mx-auto max-w-4xl px-4">
        <SectionHeading
          eyebrow="Download"
          title="Get the app"
          subtitle="Install the latest APK below, or grab an older build from previous versions."
        />

        {loading ? (
          <div className="flex items-center gap-3 text-sm text-gray-400">
            <div className="h-5 w-5 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" />
            Loading releases…
          </div>
        ) : error ? (
          <div>
            <p className="mb-4 text-sm text-red-400">{error}</p>
            <a
              href={`${GITHUB_REPO}/releases/latest`}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-2 rounded-md bg-brand-500 px-6 py-3 text-base font-semibold text-black transition-colors hover:bg-brand-400"
            >
              <GithubLogo weight="bold" size={20} /> Download from GitHub
            </a>
          </div>
        ) : latestRelease ? (
          <>
            <div className="bento flex flex-col gap-5 p-6 sm:flex-row sm:items-center sm:justify-between">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-sm font-semibold text-white">{latestRelease.name || latestRelease.tag_name}</span>
                  <span className="rounded bg-brand-500/15 px-2 py-0.5 font-mono text-[10px] uppercase tracking-wider text-brand-500">Latest</span>
                  {latestRelease.prerelease && (
                    <span className="rounded bg-yellow-500/15 px-2 py-0.5 font-mono text-[10px] uppercase tracking-wider text-yellow-400">Beta</span>
                  )}
                </div>
                <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
                  <span className="flex items-center gap-1"><Clock size={13} /> {formatDate(latestRelease.published_at)}</span>
                  {latestApk && <span className="flex items-center gap-1"><File size={13} /> {formatBytes(latestApk.size)}</span>}
                  <span>Android 7.0+</span>
                </div>
              </div>
              {latestApk && (
                <a
                  href={latestApk.browser_download_url}
                  className="inline-flex shrink-0 items-center justify-center gap-2 rounded-md bg-brand-500 px-6 py-3 text-base font-semibold text-black transition-colors hover:bg-brand-400"
                >
                  <DownloadSimple weight="bold" size={20} /> Download APK
                </a>
              )}
            </div>

            {latestRelease.body && (
              <div className="mt-4">
                <h3 className="mb-3 font-mono text-xs uppercase tracking-[0.2em] text-gray-500">What's new</h3>
                <div className="hide-scrollbar max-h-72 overflow-y-auto rounded-lg border border-white/10 bg-dark-card p-5">
                  <MarkdownBody>{latestRelease.body}</MarkdownBody>
                </div>
              </div>
            )}
          </>
        ) : null}

        {pastReleases.length > 0 && (
          <div className="mt-10">
            <button
              onClick={() => setShowPast(!showPast)}
              className="group mb-5 flex items-center gap-2 text-gray-400 transition-colors hover:text-white"
            >
              <span className="text-sm font-semibold">Previous versions ({pastReleases.length})</span>
              <motion.div animate={{ rotate: showPast ? 180 : 0 }} transition={{ duration: 0.2 }}>
                <CaretDown size={16} className="transition-colors group-hover:text-brand-500" />
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

// --- Footer ---

const FooterLink = ({ href, children, external }) => (
  <a
    href={href}
    {...(external ? { target: '_blank', rel: 'noreferrer' } : {})}
    className="text-gray-400 transition-colors hover:text-brand-500"
  >
    {children}
  </a>
);

const Footer = () => (
  <footer className="border-t border-white/[0.06] bg-black/20 pb-10 pt-16">
    <div className="mx-auto max-w-6xl px-4">
      <div className="grid grid-cols-2 gap-10 md:grid-cols-4">
        <div className="col-span-2">
          <div className="flex items-center gap-2.5">
            <img src={getAssetPath('logo.svg')} alt="KCIKTV" className="h-8 w-8 rounded-full" />
            <span className="text-lg font-extrabold tracking-tight">KCIKTV</span>
          </div>
          <p className="mt-4 max-w-xs text-sm leading-relaxed text-gray-400">
            A free, open-source Kick client for Android TV, mobile, and tablet.
          </p>
          <div className="mt-5 flex flex-wrap items-center gap-3">
            <a
              href={GITHUB_REPO}
              target="_blank"
              rel="noreferrer"
              aria-label="GitHub"
              className="flex h-10 w-10 items-center justify-center rounded-md border border-white/10 text-gray-300 transition-colors hover:border-white/20 hover:text-white"
            >
              <GithubLogo weight="fill" size={20} />
            </a>
            <a
              href="https://buymeacoffee.com/xacnio"
              target="_blank"
              rel="noreferrer"
              className="flex h-10 items-center gap-2 rounded-md border border-white/10 px-3 text-sm font-medium text-gray-300 transition-colors hover:border-white/20 hover:text-white"
            >
              <Coffee weight="regular" size={18} /> Buy me a coffee
            </a>
          </div>
        </div>

        <div>
          <h4 className="text-sm font-semibold text-white">Product</h4>
          <ul className="mt-4 space-y-2.5 text-sm">
            <li><FooterLink href="#features">Features</FooterLink></li>
            <li><FooterLink href="#screenshots">Screenshots</FooterLink></li>
            <li><FooterLink href="#download">Download</FooterLink></li>
          </ul>
        </div>

        <div>
          <h4 className="text-sm font-semibold text-white">Resources</h4>
          <ul className="mt-4 space-y-2.5 text-sm">
            <li><FooterLink href={GITHUB_REPO} external>GitHub</FooterLink></li>
            <li><FooterLink href={`${GITHUB_REPO}/issues`} external>Report Issue</FooterLink></li>
            <li><FooterLink href="privacy_policy.md">Privacy Policy</FooterLink></li>
          </ul>
        </div>
      </div>

      <div className="mt-12 flex flex-col items-center justify-between gap-3 border-t border-white/[0.06] pt-8 text-xs text-gray-500 md:flex-row">
        <span>&copy; 2026 KCIKTV. Open-source project. Not affiliated with Kick.com.</span>
        <span>Built by xacnio</span>
      </div>
    </div>
  </footer>
);

function App() {
  return (
    <div className="min-h-screen bg-dark-bg text-white selection:bg-brand-500 selection:text-black">
      <Navbar />
      <Hero />
      <Platforms />
      <Features />
      <Screenshots />
      <Permissions />
      <GetStarted />
      <DownloadSection />
      <Footer />
    </div>
  );
}

export default App;
