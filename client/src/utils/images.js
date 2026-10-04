const u = (id, w = 1600) => `https://images.unsplash.com/photo-${id}?w=${w}&q=80&auto=format&fit=crop`;

export const HERO_SLIDES = [
  { image: u('1487530811176-3780de880c2d'), caption: 'Hand-tied bouquets, made fresh every morning' },
  { image: u('1494972308805-463bc619d34e'), caption: 'Velvet red roses for the ones you love' },
  { image: u('1582794543139-8ac9cb0f7b11'), caption: 'Lush peonies for unforgettable moments' },
  { image: u('1455659817273-f96807779a8a'), caption: 'A little sunshine, delivered to their door' },
];

export const OCCASIONS = [
  { key: 'valentine', emoji: '🌹', title: "Valentine's Day", tagline: 'Celebrate Love ❤️', image: u('1526047932273-341f2a7631f9', 900) },
  { key: 'birthday', emoji: '🎂', title: 'Birthdays', tagline: 'Make Their Birthday Bloom 🌸', image: u('1563170351-be82bc888aa4', 900) },
  { key: 'proposal', emoji: '💍', title: 'Proposals', tagline: 'Say YES with Flowers 💍', image: u('1559563362-c667ba5f5480', 900) },
  { key: 'wedding', emoji: '💒', title: 'Weddings', tagline: 'Forever Starts in Bloom 💒', image: u('1525258946800-98cfd641d0de', 900) },
  { key: 'anniversary', emoji: '❤️', title: 'Anniversaries', tagline: 'Another Year of Us ❤️', image: u('1591886960571-74d43a9d4166', 900) },
  { key: 'graduation', emoji: '🎓', title: 'Graduations', tagline: 'Hats Off, Flowers Up 🎓', image: u('1508610048659-a06b669e3321', 900) },
  { key: 'celebration', emoji: '🎉', title: 'Celebrations', tagline: 'Pop the Petals 🎉', image: u('1533616688419-b7a585564566', 900) },
  { key: 'congratulations', emoji: '🌸', title: 'Congratulations', tagline: 'You Did It! 🌸', image: u('1525310072745-f49212b5ac6d', 900) },
  { key: 'romantic', emoji: '💐', title: 'Romantic Surprises', tagline: 'Surprise Their Heart 💐', image: u('1494972308805-463bc619d34e', 900) },
  { key: 'mothers-day', emoji: '🌷', title: "Mother's Day", tagline: 'For the Best Mum Ever 🌷', image: u('1595981267035-7b04ca84a82d', 900) },
  { key: 'get-well', emoji: '🌻', title: 'Get Well Soon', tagline: 'Sending Sunshine Your Way 🌻', image: u('1567696153798-9111f9cd3d0d', 900) },
  { key: 'gift', emoji: '🎁', title: 'Gifts', tagline: 'Just Because 🎁', image: u('1610397648930-477b8c7f0943', 900) },
];

export const ABOUT_IMAGES = {
  story: u('1487530811176-3780de880c2d', 1000),
  mission: u('1490750967868-88aa4486c946', 1000),
  florist: u('1563241527-3004b7be0ffd', 1000),
};

export const AUTH_IMAGE = u('1582794543139-8ac9cb0f7b11', 1200);

export const FALLBACK_IMAGE = u('1490750967868-88aa4486c946', 600);
