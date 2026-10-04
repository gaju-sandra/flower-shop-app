-- =====================================================================
-- Bloom & Co. Flower Delivery - relational schema (PostgreSQL)
-- Operational / transactional data lives here. Documents (contact
-- messages, audit trail, notification log) live in MongoDB.
-- =====================================================================

-- ---------- Users & authentication ----------
CREATE TABLE users (
  id              SERIAL PRIMARY KEY,
  first_name      VARCHAR(60)  NOT NULL,
  last_name       VARCHAR(60)  NOT NULL,
  email           VARCHAR(160) NOT NULL,
  phone           VARCHAR(20),
  password_hash   VARCHAR(100),                    -- NULL for OAuth-only accounts
  role            VARCHAR(20)  NOT NULL DEFAULT 'customer'
                  CHECK (role IN ('customer', 'staff', 'admin')),
  staff_role      VARCHAR(30)
                  CHECK (staff_role IN ('order_manager', 'delivery_staff', 'customer_support', 'inventory_staff')),
  status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active', 'disabled')),
  avatar_url      VARCHAR(500),
  oauth_provider  VARCHAR(20),
  oauth_subject   VARCHAR(100),
  last_login_at   TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT staff_role_only_for_staff CHECK (staff_role IS NULL OR role = 'staff'),
  CONSTRAINT uq_users_oauth UNIQUE (oauth_provider, oauth_subject)
);
CREATE UNIQUE INDEX uq_users_email ON users (LOWER(email));
CREATE INDEX idx_users_role ON users (role);

CREATE TABLE refresh_tokens (
  id          SERIAL PRIMARY KEY,
  user_id     INT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  CHAR(64)     NOT NULL UNIQUE,      -- SHA-256 of the opaque token
  remember    BOOLEAN      NOT NULL DEFAULT FALSE,
  expires_at  TIMESTAMPTZ  NOT NULL,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE password_resets (
  id          SERIAL PRIMARY KEY,
  user_id     INT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  CHAR(64)     NOT NULL UNIQUE,
  expires_at  TIMESTAMPTZ  NOT NULL,
  used_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE addresses (
  id                    SERIAL PRIMARY KEY,
  user_id               INT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  label                 VARCHAR(40)  NOT NULL DEFAULT 'Home',
  recipient_name        VARCHAR(120),
  phone                 VARCHAR(20),
  province              VARCHAR(60)  NOT NULL,
  district              VARCHAR(60)  NOT NULL,
  sector                VARCHAR(60)  NOT NULL,
  street                VARCHAR(200) NOT NULL,
  location_description  VARCHAR(300),
  is_default            BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_addresses_user ON addresses (user_id);
-- at most one default address per user
CREATE UNIQUE INDEX uq_addresses_default ON addresses (user_id) WHERE is_default;

-- ---------- Catalogue ----------
CREATE TABLE categories (
  id           SERIAL PRIMARY KEY,
  name         VARCHAR(60)  NOT NULL UNIQUE,
  slug         VARCHAR(80)  NOT NULL UNIQUE,
  description  VARCHAR(300)
);

CREATE TABLE products (
  id                SERIAL PRIMARY KEY,
  name              VARCHAR(120)  NOT NULL,
  slug              VARCHAR(140)  NOT NULL UNIQUE,
  description       TEXT          NOT NULL DEFAULT '',
  category_id       INT           REFERENCES categories(id) ON DELETE SET NULL,
  occasions         TEXT[]        NOT NULL DEFAULT '{}',
  price             NUMERIC(12,2) NOT NULL CHECK (price >= 0),
  discount_percent  SMALLINT      NOT NULL DEFAULT 0 CHECK (discount_percent BETWEEN 0 AND 90),
  stock             INT           NOT NULL DEFAULT 0 CHECK (stock >= 0),
  image_url         VARCHAR(500),
  status            VARCHAR(20)   NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
  sold_count        INT           NOT NULL DEFAULT 0,
  rating_avg        NUMERIC(3,2)  NOT NULL DEFAULT 0,
  rating_count      INT           NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_products_category ON products (category_id);
CREATE INDEX idx_products_status_created ON products (status, created_at DESC);
CREATE INDEX idx_products_price ON products (price);
CREATE INDEX idx_products_sold ON products (sold_count DESC);
CREATE INDEX idx_products_name ON products (LOWER(name));
CREATE INDEX idx_products_occasions ON products USING GIN (occasions);

-- ---------- Shopping ----------
CREATE TABLE cart (
  id          SERIAL PRIMARY KEY,
  user_id     INT          NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE cart_items (
  id               SERIAL PRIMARY KEY,
  cart_id          INT      NOT NULL REFERENCES cart(id) ON DELETE CASCADE,
  product_id       INT      NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  quantity         INT      NOT NULL CHECK (quantity BETWEEN 1 AND 99),
  saved_for_later  BOOLEAN  NOT NULL DEFAULT FALSE,
  CONSTRAINT uq_cart_product UNIQUE (cart_id, product_id)
);

CREATE TABLE wishlist (
  id          SERIAL PRIMARY KEY,
  user_id     INT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  product_id  INT          NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_wishlist UNIQUE (user_id, product_id)
);

CREATE TABLE promotions (
  id                SERIAL PRIMARY KEY,
  code              VARCHAR(30)   NOT NULL UNIQUE,
  title             VARCHAR(120)  NOT NULL,
  description       VARCHAR(300),
  discount_percent  SMALLINT      NOT NULL CHECK (discount_percent BETWEEN 1 AND 90),
  min_order         NUMERIC(12,2) NOT NULL DEFAULT 0,
  category_id       INT           REFERENCES categories(id) ON DELETE SET NULL,
  starts_at         DATE          NOT NULL DEFAULT CURRENT_DATE,
  ends_at           DATE,
  active            BOOLEAN       NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  CHECK (ends_at IS NULL OR ends_at >= starts_at)
);

CREATE TABLE gift_options (
  id      SERIAL PRIMARY KEY,
  code    VARCHAR(30)   NOT NULL UNIQUE,
  name    VARCHAR(60)   NOT NULL,
  icon    VARCHAR(8)    NOT NULL,
  price   NUMERIC(12,2) NOT NULL CHECK (price >= 0),
  active  BOOLEAN       NOT NULL DEFAULT TRUE
);

-- ---------- Orders ----------
CREATE TABLE orders (
  id                    SERIAL PRIMARY KEY,
  order_number          VARCHAR(20)   UNIQUE,              -- FLW-2026-00125, set after insert
  user_id               INT           NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  subtotal              NUMERIC(12,2) NOT NULL CHECK (subtotal >= 0),
  discount              NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
  gift_total            NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (gift_total >= 0),
  delivery_fee          NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (delivery_fee >= 0),
  total_amount          NUMERIC(12,2) NOT NULL CHECK (total_amount >= 0),
  promotion_id          INT           REFERENCES promotions(id) ON DELETE SET NULL,
  customer_name         VARCHAR(120)  NOT NULL,
  email                 VARCHAR(160)  NOT NULL,
  phone                 VARCHAR(20)   NOT NULL,
  province              VARCHAR(60)   NOT NULL,
  district              VARCHAR(60)   NOT NULL,
  sector                VARCHAR(60)   NOT NULL,
  street                VARCHAR(200)  NOT NULL,
  location_description  VARCHAR(300),
  delivery_address      VARCHAR(500)  NOT NULL,           -- denormalised full address snapshot
  delivery_date         DATE          NOT NULL,
  delivery_time         VARCHAR(20)   NOT NULL,
  instructions          VARCHAR(500),
  recipient_name        VARCHAR(120),
  message_type          VARCHAR(30),
  gift_message          VARCHAR(500),
  gift_options          JSONB         NOT NULL DEFAULT '[]',
  payment_method        VARCHAR(30)   NOT NULL
                        CHECK (payment_method IN ('mtn_momo', 'airtel_money', 'card', 'cash_on_delivery')),
  status                VARCHAR(30)   NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'confirmed', 'preparing', 'ready', 'out_for_delivery', 'delivered', 'cancelled')),
  payment_status        VARCHAR(20)   NOT NULL DEFAULT 'pending'
                        CHECK (payment_status IN ('pending', 'paid', 'failed', 'refunded')),
  created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_created ON orders (created_at);

CREATE TABLE order_items (
  id            SERIAL PRIMARY KEY,
  order_id      INT           NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  product_id    INT           REFERENCES products(id) ON DELETE SET NULL,
  product_name  VARCHAR(120)  NOT NULL,                  -- snapshot
  image_url     VARCHAR(500),
  quantity      INT           NOT NULL CHECK (quantity > 0),
  price         NUMERIC(12,2) NOT NULL CHECK (price >= 0) -- unit price paid
);
CREATE INDEX idx_order_items_order ON order_items (order_id);
CREATE INDEX idx_order_items_product ON order_items (product_id);

CREATE TABLE order_status_history (
  id          SERIAL PRIMARY KEY,
  order_id    INT          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  status      VARCHAR(30)  NOT NULL,
  changed_by  INT          REFERENCES users(id) ON DELETE SET NULL,
  note        VARCHAR(300),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_status_history_order ON order_status_history (order_id);

CREATE TABLE payments (
  id                     SERIAL PRIMARY KEY,
  order_id               INT           NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  payment_method         VARCHAR(30)   NOT NULL,
  amount                 NUMERIC(12,2) NOT NULL CHECK (amount >= 0),
  transaction_reference  VARCHAR(40)   NOT NULL UNIQUE,
  payer_phone            VARCHAR(20),
  card_last4             CHAR(4),
  payment_status         VARCHAR(20)   NOT NULL DEFAULT 'pending'
                         CHECK (payment_status IN ('pending', 'paid', 'failed', 'refunded')),
  payment_date           TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_date ON payments (payment_date);

CREATE TABLE deliveries (
  id                SERIAL PRIMARY KEY,
  order_id          INT          NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE,
  staff_id          INT          REFERENCES users(id) ON DELETE SET NULL,
  delivery_address  VARCHAR(500) NOT NULL,
  delivery_date     DATE         NOT NULL,
  delivery_time     VARCHAR(20)  NOT NULL,
  delivery_status   VARCHAR(20)  NOT NULL DEFAULT 'pending'
                    CHECK (delivery_status IN ('pending', 'assigned', 'picked_up', 'on_the_way', 'delivered')),
  notes             VARCHAR(300),
  delivered_at      TIMESTAMPTZ,
  updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_deliveries_staff ON deliveries (staff_id);
CREATE INDEX idx_deliveries_status_date ON deliveries (delivery_status, delivery_date);

CREATE TABLE reviews (
  id          SERIAL PRIMARY KEY,
  user_id     INT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  product_id  INT          NOT NULL REFERENCES products(id) ON DELETE CASCADE,
  rating      SMALLINT     NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment     VARCHAR(1000),
  status      VARCHAR(20)  NOT NULL DEFAULT 'visible' CHECK (status IN ('visible', 'hidden')),
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_review_user_product UNIQUE (user_id, product_id)
);
CREATE INDEX idx_reviews_product ON reviews (product_id);

-- ---------- Configuration ----------
CREATE TABLE settings (
  key         VARCHAR(60) PRIMARY KEY,
  value       JSONB       NOT NULL,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
