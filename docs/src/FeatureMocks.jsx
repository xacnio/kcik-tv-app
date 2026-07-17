import React from 'react';
import { CaretLeft, X, Gift, Smiley, PaperPlaneRight, Medal } from '@phosphor-icons/react';

// Phone-frame "simulations" rebuilt 1:1 from the app's Android layouts
// (bottom_sheet_chat_settings.xml, item_chat_message.xml) and colors.xml.
// Palette: bg #0B0B0B · panel #141414 · card #1C1C1E · divider #2C2C2E · green #53FC18.

const C = {
  bg: '#0B0B0B',
  panel: '#141414',
  card: '#1C1C1E',
  divider: '#2C2C2E',
  green: '#53FC18',
  sub: '#5A5A5E',
  sub2: '#888888',
  label: '#666666',
};

const PhoneFrame = ({ children, bg }) => (
  <div className="relative w-[230px] shrink-0 rounded-[2.2rem] border-[7px] border-[#1b2026] shadow-xl shadow-black/50">
    <div className="absolute left-1/2 top-2 z-20 h-1.5 w-14 -translate-x-1/2 rounded-full bg-white/15" />
    <div className="h-[470px] overflow-hidden rounded-[1.7rem]" style={{ background: bg }}>{children}</div>
  </div>
);

// Shared sheet header (52dp → scaled): back arrow, bold title, close.
const SheetHeader = ({ title, back }) => (
  <>
    <div className="flex h-[46px] items-center pl-1.5 pr-1">
      {back ? <CaretLeft size={17} weight="bold" className="mx-1.5 text-white" /> : <span className="w-3" />}
      <span className="flex-1 text-[15px] font-bold text-white">{title}</span>
      <X size={15} weight="bold" className="mx-2.5" style={{ color: C.sub2 }} />
    </div>
    <div className="h-px w-full" style={{ background: 'rgba(255,255,255,0.09)' }} />
  </>
);

// Material SwitchCompat with the green accent (colorSecondary = brand_green).
const Switch = ({ on }) => (
  <div className="relative h-[18px] w-[34px] shrink-0">
    <div
      className="absolute inset-x-0 top-1/2 h-[14px] -translate-y-1/2 rounded-full"
      style={{ background: on ? 'rgba(83,252,24,0.4)' : '#39393b' }}
    />
    <div
      className="absolute top-1/2 h-[18px] w-[18px] -translate-y-1/2 rounded-full shadow"
      style={{ left: on ? 16 : 0, background: on ? C.green : '#b8b8b8' }}
    />
  </div>
);

// ===== Settings screen: Highlights & mentions sub-page =====

const highlightRows = [
  ['Mentions', null, true],
  ['Moderator Messages', null, true],
  ['OG Messages', null, true],
  ['Highlight as Background', null, false],
  ['Vibrate on Mention', null, true],
  ['Sound on Mention', null, true],
  ['Send Notification', null, true],
  ['Nick Detection', 'Alert when your name appears without @', true],
];

const SettingsScreen = () => (
  <div className="flex h-full flex-col">
    <SheetHeader title="Highlighted Messages" back />
    <div className="px-4 pt-1">
      {highlightRows.map(([title, subtitle, on], i) => (
        <div key={title}>
          <div className="flex items-center gap-3" style={{ minHeight: 44 }}>
            <div className="flex-1 py-2">
              <div className="text-[13px] text-white">{title}</div>
              {subtitle && <div className="mt-0.5 text-[11px]" style={{ color: C.sub }}>{subtitle}</div>}
            </div>
            <Switch on={on} />
          </div>
          {i < highlightRows.length - 1 && <div className="h-px" style={{ background: C.divider }} />}
        </div>
      ))}
    </div>
  </div>
);

// ===== Chat screen: message list (item_chat_message.xml) =====

const Lvl = ({ n }) => (
  <span
    className="mr-1 inline-flex items-center rounded-[3px] px-1 align-middle text-[9px] font-bold leading-[1.5] text-black"
    style={{ background: C.green }}
  >
    {n}
  </span>
);

const ChatLine = ({ level, name, color, text, highlight }) => (
  <div
    className="rounded px-1 py-[3px] text-[13px] leading-snug"
    style={highlight ? { background: 'rgba(83,252,24,0.10)' } : undefined}
  >
    <Lvl n={level} />
    <span className="font-bold" style={{ color }}>{name}</span>
    <span className="text-white/90">: {text}</span>
  </div>
);

const ChatScreen = () => (
  <div className="flex h-full flex-col">
    <div
      className="m-2 flex items-center gap-1.5 rounded-lg px-2.5 py-2"
      style={{ background: 'rgba(83,252,24,0.08)', border: '1px solid rgba(83,252,24,0.25)' }}
    >
      <Gift size={13} weight="fill" className="text-brand-500" />
      <span className="text-[11px] font-semibold text-brand-400">ninjastorm</span>
      <span className="text-[11px] text-gray-300">sent 50 KICKS</span>
    </div>
    <div className="flex-1 space-y-0.5 overflow-hidden px-1.5">
      <ChatLine level={42} name="eddie" color="#53FC18" text="lets gooo 🔥" highlight />
      <ChatLine level={7} name="viewer_01" color="#60a5fa" text="GG well played" />
      <ChatLine level={18} name="k2theaa" color="#f472b6" text="POG" />
      <ChatLine level={3} name="newbie" color="#fbbf24" text="first time here :)" />
      <ChatLine level={56} name="modguy" color="#34d399" text="welcome everyone" />
      <ChatLine level={12} name="ggwp" color="#a78bfa" text="clip that one" />
      <ChatLine level={9} name="randomdude" color="#f87171" text="lol" />
      <ChatLine level={23} name="streamfan" color="#22d3ee" text="W stream" />
    </div>
    <div className="flex items-center gap-2 border-t px-2 py-2" style={{ borderColor: 'rgba(255,255,255,0.08)' }}>
      <Smiley size={19} className="text-gray-500" />
      <div className="flex-1 rounded-full px-3 py-1.5 text-[11px] text-gray-500" style={{ background: C.card }}>
        Send a message…
      </div>
      <div className="grid h-6 w-6 place-items-center rounded-full bg-brand-500">
        <PaperPlaneRight size={11} weight="fill" className="text-black" />
      </div>
    </div>
  </div>
);

// ===== Identity & levels screen (profileViewContainer) =====

const SectionLabel = ({ children }) => (
  <div className="text-[10px] font-bold uppercase tracking-[0.08em]" style={{ color: C.label }}>{children}</div>
);

const badgeColors = ['#53FC18', '#fbbf24', '#60a5fa', '#f472b6'];
const nameColors = [
  '#53FC18', '#22d3ee', '#60a5fa', '#a78bfa', '#f472b6', '#f87171', '#fbbf24', '#34d399',
  '#fb923c', '#e879f9', '#38bdf8', '#4ade80', '#facc15', '#fca5a5', '#c084fc', '#2dd4bf',
];

const IdentityScreen = () => (
  <div className="flex h-full flex-col">
    <SheetHeader title="Chat Identity" back />
    <div className="flex-1 overflow-hidden p-4">
      <SectionLabel>Preview</SectionLabel>
      <div className="mt-2 flex items-center rounded-[14px] px-3.5 py-3" style={{ background: C.card }}>
        <span className="mr-1 inline-block h-3.5 w-3.5 rounded-[3px]" style={{ background: C.green }} />
        <span className="text-[13px] font-bold" style={{ color: C.green }}>eddie</span>
        <span className="text-[13px]" style={{ color: '#CCCCCC' }}>: Hello world!</span>
      </div>

      <div className="mt-3 flex items-center rounded-[14px] p-2.5" style={{ background: C.card }}>
        <div className="grid h-7 w-7 place-items-center rounded-md" style={{ background: 'rgba(83,252,24,0.15)' }}>
          <Medal size={15} weight="fill" className="text-brand-500" />
        </div>
        <div className="ml-2.5 flex-1">
          <div className="text-[11px] font-bold text-white">Level 42</div>
          <div className="mt-1 h-[3px] w-full overflow-hidden rounded-full" style={{ background: 'rgba(255,255,255,0.1)' }}>
            <div className="h-full rounded-full" style={{ width: '62%', background: C.green }} />
          </div>
          <div className="mt-0.5 text-[9px]" style={{ color: C.green }}>62% to Level 43</div>
        </div>
      </div>

      <div className="mt-5 text-[12px] font-bold text-white">Badges</div>
      <div className="mt-0.5 text-[11px]" style={{ color: C.sub2 }}>Maximum 4 badges can be shown</div>
      <div className="mt-3 text-[11px]" style={{ color: C.sub2 }}>Channel badges: visible on broadcaster&apos;s channel</div>
      <div className="mt-2 flex gap-1">
        {badgeColors.map((c, i) => (
          <div
            key={c}
            className="grid h-9 w-9 place-items-center rounded-md"
            style={{ outline: i === 0 ? `2px solid ${C.green}` : 'none', outlineOffset: -2 }}
          >
            <span className="h-5 w-5 rounded" style={{ background: c }} />
          </div>
        ))}
      </div>

      <div className="mt-5"><SectionLabel>Name color</SectionLabel></div>
      <div className="mt-2 grid grid-cols-8 gap-1.5">
        {nameColors.map((c, i) => (
          <span
            key={c}
            className="h-4 w-4 rounded-full"
            style={{ background: c, outline: i === 0 ? '2px solid #fff' : 'none', outlineOffset: 1 }}
          />
        ))}
      </div>
    </div>
  </div>
);

// ===== Engagement screen: Daily Reward claim (dialog_daily_reward.xml) =====

const rarityColors = ['#9E9E9E', '#4CAF50', '#2D9CDB', '#F5455C', '#FF9800', '#7B61FF'];

const RewardScreen = () => (
  <div className="flex h-full flex-col items-center px-5 pb-4 pt-7 text-center" style={{ background: '#000' }}>
    <div className="grid h-9 w-9 place-items-center rounded-full" style={{ background: 'rgba(255,255,255,0.08)' }}>
      <Gift size={17} weight="fill" className="text-brand-500" />
    </div>
    <div className="mt-3 text-[13px] font-bold text-white">Claim Your Daily Reward</div>
    <div className="mt-1.5 px-1 text-[10px] leading-snug" style={{ color: C.sub2 }}>
      Watch a bit each day for a shot at an emote or badge.
    </div>

    <div
      className="relative mt-4 flex w-[104px] items-center justify-center overflow-hidden rounded-xl"
      style={{
        aspectRatio: '128/169',
        background: 'linear-gradient(160deg, rgba(123,97,255,0.35), rgba(123,97,255,0.05))',
        border: '1px solid rgba(123,97,255,0.55)',
      }}
    >
      <Medal size={28} weight="fill" style={{ color: '#7B61FF' }} />
    </div>

    <div className="mt-4 flex items-center gap-1.5">
      {rarityColors.map((c) => (
        <span key={c} className="h-2.5 w-2.5 rounded-[3px]" style={{ background: c }} />
      ))}
    </div>

    <div className="mt-auto w-full pt-4">
      <div className="w-full rounded-md py-2.5 text-[12px] font-bold text-black" style={{ background: C.green }}>
        Claim
      </div>
      <div className="mt-2 text-[9px]" style={{ color: C.sub }}>Resets in 6h 12m</div>
    </div>
  </div>
);

const ShotScreen = ({ src }) => (
  <img src={src} alt="" loading="lazy" className="h-full w-full object-cover object-top" />
);

const SCREENS = { chat: ChatScreen, identity: IdentityScreen, settings: SettingsScreen, rewards: RewardScreen };
const SCREEN_BG = { chat: C.bg, rewards: '#000' };

export default function FeatureMock({ mock, shotSrc }) {
  const Screen = mock ? SCREENS[mock] : null;
  return (
    <PhoneFrame bg={SCREEN_BG[mock] || C.panel}>
      {Screen ? <Screen /> : <ShotScreen src={shotSrc} />}
    </PhoneFrame>
  );
}
