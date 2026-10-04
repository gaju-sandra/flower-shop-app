const img = (id, w = 800) => `https://images.unsplash.com/photo-${id}?w=${w}&q=80&auto=format&fit=crop`;

export const CATEGORIES = [
  ['Roses', 'Timeless roses in every colour'],
  ['Bouquets', 'Hand-tied mixed bouquets by our florists'],
  ['Wedding Flowers', 'Bridal bouquets and wedding arrangements'],
  ['Birthday Flowers', 'Bright blooms to celebrate another year'],
  ['Romantic Flowers', 'Say it with love'],
  ['Gift Flowers', 'Thoughtful flowers for any gesture'],
  ['Seasonal Flowers', 'Fresh picks of the season'],
];

// [name, category, price, discount%, stock, imageId, occasions, sold, description]
export const PRODUCTS = [
  ['Red Roses', 'Roses', 15000, 0, 40, '1494972308805-463bc619d34e', ['valentine', 'romantic', 'anniversary'], 182,
    'A dozen velvety long-stem red roses - the classic declaration of love, wrapped in kraft paper and satin ribbon.'],
  ['Pink Tulips', 'Seasonal Flowers', 12500, 0, 35, '1561181286-d3fee7d55364', ['birthday', 'mothers-day', 'thank-you'], 121,
    'Ten fresh pink tulips that open gracefully over the week. Light, cheerful and perfect for any spring moment.'],
  ['Lilies', 'Gift Flowers', 18000, 0, 20, '1525310072745-f49212b5ac6d', ['congratulations', 'sympathy', 'thank-you'], 74,
    'Fragrant pink oriental lilies that fill the room with a sweet scent. Elegant and long-lasting.'],
  ['Sunflowers', 'Seasonal Flowers', 10000, 0, 50, '1455659817273-f96807779a8a', ['get-well', 'birthday', 'congratulations'], 158,
    'A sunny bunch of sunflowers to brighten anyone\'s day. Guaranteed to bring a smile.'],
  ['Wildflowers', 'Seasonal Flowers', 8500, 0, 30, '1490750967868-88aa4486c946', ['thank-you', 'get-well'], 66,
    'A free-spirited mix of seasonal wildflowers, picked fresh and loosely arranged for a natural, meadow look.'],
  ['Peonies', 'Romantic Flowers', 22000, 0, 15, '1582794543139-8ac9cb0f7b11', ['wedding', 'anniversary', 'romantic'], 97,
    'Lush, ruffled pink peonies - the ultimate luxury flower. Soft, romantic and beautifully fragrant.'],
  ['Orchids', 'Gift Flowers', 25000, 10, 12, '1610397648930-477b8c7f0943', ['congratulations', 'gift', 'thank-you'], 41,
    'An exotic pink phalaenopsis orchid stem. Elegant, modern and blooms for weeks with very little care.'],
  ['Daisies', 'Seasonal Flowers', 7500, 0, 45, '1508784411316-02b8cd4d3a3a', ['get-well', 'birthday'], 88,
    'Simple, pure and joyful white daisies with golden hearts - a breath of fresh air.'],
  ['Carnations', 'Gift Flowers', 9000, 0, 40, '1548094990-c16ca90f1f0d', ['mothers-day', 'thank-you'], 59,
    'Ruffled red carnations symbolising admiration and deep love. Long-lasting and great value.'],
  ['Hydrangeas', 'Wedding Flowers', 16000, 0, 18, '1471696035578-3d8c78d99684', ['wedding', 'thank-you'], 47,
    'Big, cloud-like blue hydrangea heads - a statement on any table and a wedding favourite.'],
  ['Lavender', 'Gift Flowers', 11000, 0, 25, '1565011523534-747a8601f10a', ['get-well', 'thank-you', 'gift'], 52,
    'Calming, fragrant lavender in a ceramic pot. Brings serenity to any room and dries beautifully.'],
  ['Mixed Bouquet', 'Bouquets', 20000, 0, 25, '1487530811176-3780de880c2d', ['birthday', 'congratulations', 'celebration'], 143,
    'Our florist\'s signature mix of roses, astrantia, berries and eucalyptus - every bouquet is unique.'],
  ['Rainbow Roses', 'Roses', 28000, 0, 10, '1508610048659-a06b669e3321', ['birthday', 'celebration', 'graduation'], 38,
    'A magical bunch of rainbow-dyed roses. Bold, playful and unforgettable.'],
  ['Blush Pink Roses', 'Roses', 17000, 15, 30, '1591886960571-74d43a9d4166', ['romantic', 'mothers-day', 'anniversary'], 105,
    'Soft garden roses in blush and cream tones, arranged for a gentle, romantic feel.'],
  ['Bridal Bouquet', 'Wedding Flowers', 45000, 0, 8, '1525258946800-98cfd641d0de', ['wedding', 'proposal'], 23,
    'A hand-tied bridal bouquet of peach roses, white spray roses and baby\'s breath. Made to order.'],
  ['Birthday Bloom Bouquet', 'Birthday Flowers', 19500, 0, 22, '1563170351-be82bc888aa4', ['birthday', 'celebration'], 112,
    'Peach roses, pink carnations and wild greens - a happy, colourful way to say Happy Birthday!'],
  ['Heart of Roses', 'Romantic Flowers', 35000, 20, 10, '1526047932273-341f2a7631f9', ['valentine', 'proposal', 'romantic'], 64,
    'Pink and yellow roses arranged in a heart - the most romantic surprise you can send.'],
  ['Peach Garden Bouquet', 'Bouquets', 23000, 0, 16, '1595981267035-7b04ca84a82d', ['mothers-day', 'birthday', 'thank-you'], 71,
    'A garden-style bouquet of peach roses, carnations and wax flowers in a glass vase.'],
  ['White Tulips', 'Seasonal Flowers', 13500, 0, 28, '1589994160839-163cd867cfe8', ['wedding', 'sympathy', 'thank-you'], 44,
    'Crisp, pure white tulips - minimal, modern and endlessly elegant.'],
  ['Dahlia Dream', 'Seasonal Flowers', 14500, 0, 14, '1546842931-886c185b4c8c', ['birthday', 'congratulations'], 29,
    'Peach and coral dahlias with layered petals - a showstopper of the season.'],
  ['Get Well Sunshine Wrap', 'Gift Flowers', 15500, 0, 20, '1567696153798-9111f9cd3d0d', ['get-well', 'gift'], 57,
    'Sunflowers and white blooms wrapped in kraft paper with a get-well card. Pure sunshine.'],
  ['Celebration Bouquet', 'Birthday Flowers', 21000, 0, 18, '1533616688419-b7a585564566', ['congratulations', 'graduation', 'celebration'], 63,
    'Coral roses, snapdragons and thistle in a lively, celebratory arrangement.'],
  ['Single Red Rose', 'Romantic Flowers', 3500, 0, 80, '1559563362-c667ba5f5480', ['valentine', 'romantic'], 210,
    'One perfect red rose, gift-wrapped. Small gesture, big meaning.'],
  ['Wedding Blush Arrangement', 'Wedding Flowers', 38000, 0, 6, '1563241527-3004b7be0ffd', ['wedding', 'anniversary'], 19,
    'Blush peonies, garden roses and eucalyptus in a rustic arrangement for tables and ceremonies.'],
  ['Flame Tulips', 'Seasonal Flowers', 14000, 0, 20, '1468327768560-75b778cbb551', ['birthday', 'thank-you'], 31,
    'Striking red-and-white flame tulips - a cheerful, unusual twist on a classic.'],
  ['Anthurium', 'Gift Flowers', 19000, 0, 12, '1567748157439-651aca2ff064', ['gift', 'congratulations'], 15,
    'Glossy red anthuriums - tropical, bold and long-lasting. A gift that lasts for weeks.'],
].map(([name, category, price, discount, stock, imageId, occasions, sold, description]) => ({
  name, category, price, discount, stock, imageUrl: img(imageId), occasions, sold, description,
}));

export const GIFT_OPTIONS = [
  ['gift_wrap', 'Gift wrapping', '🎁', 2000],
  ['greeting_card', 'Greeting card', '💌', 1500],
  ['chocolate', 'Chocolate box', '🍫', 8000],
  ['teddy_bear', 'Teddy bear', '🧸', 12000],
  ['balloons', 'Balloons', '🎈', 5000],
  ['premium_packaging', 'Premium packaging', '💐', 6000],
];

export const PROMOTIONS = [
  ['VALENTINE20', "20% OFF Valentine's Collection ❤️", 'Celebrate love with 20% off every order over RWF 20,000.', 20, 20000],
  ['BLOOM10', 'Welcome gift: 10% off', '10% off your first bouquet - no minimum.', 10, 0],
  ['MOTHERSDAY15', "15% off Mother's Day flowers 🌷", 'Spoil mum with 15% off orders over RWF 30,000.', 15, 30000],
];

export const STAFF = [
  ['Aline', 'Uwase', 'aline.staff@bloomandco.rw', '0788000101', 'order_manager'],
  ['Eric', 'Habimana', 'eric.delivery@bloomandco.rw', '0788000102', 'delivery_staff'],
  ['Grace', 'Mukamana', 'grace.support@bloomandco.rw', '0788000103', 'customer_support'],
  ['Jean', 'Niyonzima', 'jean.inventory@bloomandco.rw', '0788000104', 'inventory_staff'],
];

export const CUSTOMERS = [
  ['Melissa', 'Ineza', 'melissa@example.com', '0788111222', ['Kigali City', 'Gasabo', 'Kimihurura', 'KG 9 Ave, House 12']],
  ['Kevin', 'Mugisha', 'kevin@example.com', '0788333444', ['Kigali City', 'Kicukiro', 'Niboye', 'KK 15 Rd, House 4']],
  ['Diane', 'Uwimana', 'diane@example.com', '0722555666', ['Kigali City', 'Nyarugenge', 'Nyamirambo', 'KN 2 St, House 31']],
  ['Patrick', 'Nkurunziza', 'patrick@example.com', '0738777888', ['Southern Province', 'Huye', 'Ngoma', 'Main Rd, near UR campus']],
  ['Sandrine', 'Ishimwe', 'sandrine@example.com', '0789999000', ['Kigali City', 'Gasabo', 'Remera', 'KG 11 Ave, Apt 3B']],
];

export const REVIEW_TEXTS = [
  [5, 'Absolutely stunning! Delivered on time and the flowers lasted more than a week.'],
  [5, 'My girlfriend loved them. The handwritten message was a lovely touch ❤️'],
  [4, 'Beautiful arrangement, delivery was a little later than the slot but still great.'],
  [5, 'Fresh, fragrant and exactly like the photo. Will order again!'],
  [4, 'Very pretty and good value. Packaging was premium.'],
  [5, 'Best florist in Kigali. My mum cried happy tears 🌷'],
];
