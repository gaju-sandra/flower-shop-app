import { ZodError } from 'zod';
import { badRequest } from '../utils/http.js';

/** Validates & coerces req[source] with a zod schema; replaces it with the parsed value. */
export const validate =
  (schema, source = 'body') =>
  (req, _res, next) => {
    try {
      const parsed = schema.parse(req[source] ?? {});
      if (source === 'query') {
        // req.query is a getter in Express 5-style routers; assign key by key
        Object.keys(req.query).forEach((k) => delete req.query[k]);
        Object.assign(req.query, parsed);
      } else {
        req[source] = parsed;
      }
      next();
    } catch (err) {
      if (err instanceof ZodError) {
        const details = err.errors.map((e) => ({ field: e.path.join('.'), message: e.message }));
        return next(badRequest(details[0]?.message || 'Invalid input', details));
      }
      next(err);
    }
  };
