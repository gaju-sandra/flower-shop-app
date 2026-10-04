import 'dotenv/config';

const required = (name, fallback) => {
  const value = process.env[name] ?? fallback;
  if (value === undefined || value === '') throw new Error(`Missing environment variable ${name}`);
  return value;
};

export const env = {
  nodeEnv: process.env.NODE_ENV || 'development',
  isTest: process.env.NODE_ENV === 'test',
  isProd: process.env.NODE_ENV === 'production',
  port: Number(process.env.PORT || 5000),
  clientUrl: process.env.CLIENT_URL || 'http://localhost:5173',
  serverUrl: process.env.SERVER_URL || 'http://localhost:5000',

  databaseUrl: required('DATABASE_URL', 'postgres://postgres@localhost:5432/flower_shop_db'),
  mongoUri: process.env.MONGO_URI || '',
  rabbitUrl: process.env.RABBITMQ_URL || '',
  runWorkerInProcess: (process.env.RUN_WORKER_IN_PROCESS ?? 'true') === 'true',

  jwtAccessSecret: required('JWT_ACCESS_SECRET', 'dev-access-secret'),
  jwtRefreshSecret: required('JWT_REFRESH_SECRET', 'dev-refresh-secret'),
  accessTokenTtl: process.env.ACCESS_TOKEN_TTL || '15m',
  refreshTtlDays: 1, // session cookie lifetime without "remember me"
  refreshTtlDaysRemember: 30,

  google: {
    clientId: process.env.GOOGLE_CLIENT_ID || '',
    clientSecret: process.env.GOOGLE_CLIENT_SECRET || '',
    redirectUri:
      process.env.GOOGLE_REDIRECT_URI || 'http://localhost:5000/api/auth/oauth/google/callback',
  },

  smtp: {
    host: process.env.SMTP_HOST || '',
    port: Number(process.env.SMTP_PORT || 587),
    user: process.env.SMTP_USER || '',
    pass: process.env.SMTP_PASS || '',
    from: process.env.MAIL_FROM || 'Bloom & Co. <hello@bloomandco.rw>',
  },
  sms: {
    apiKey: process.env.SMS_API_KEY || '',
    sender: process.env.SMS_SENDER || 'BLOOMCO',
  },
};
