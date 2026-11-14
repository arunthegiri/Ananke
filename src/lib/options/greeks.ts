/**
 * Black-Scholes-Merton and Black-76 pricing, greeks and implied volatility
 * (multi-asset P2, issue #8). Pure functions, no I/O, no clock.
 *
 * Units — chosen to match what Alpaca's option snapshots report, so model
 * values and vendor values can sit in the same column (verified against live
 * AAPL contracts; see tests/options-greeks.test.mjs):
 *   · delta     per $1 of underlying
 *   · gamma     per $1 of underlying
 *   · theta     per CALENDAR DAY (annual theta / 365)
 *   · vega      per 1 vol point (σ + 0.01)
 *   · rho       per 1 rate point (r + 0.01)
 *   · σ, r, q   annualised decimals (0.25 = 25%)
 *   · t         years (365-day basis)
 *
 * American early exercise is ignored: for the IV/greeks *display* fallback on
 * equity options this is the standard approximation; it is not a pricer for
 * deep-ITM puts.
 */

export type Right = "C" | "P";

export interface BsInput {
  /** Spot (BSM) or forward (Black-76). */
  s: number;
  k: number;
  /** Years to expiry. */
  t: number;
  /** Continuously-compounded risk-free rate. */
  r: number;
  /** Continuous dividend yield (BSM only; Black-76 ignores it). */
  q?: number;
  sigma: number;
  right: Right;
}

export interface Greeks {
  price: number;
  delta: number;
  gamma: number;
  theta: number;
  vega: number;
  rho: number;
}

const SQRT_2PI = Math.sqrt(2 * Math.PI);

/** Standard normal pdf. */
export function normPdf(x: number): number {
  return Math.exp(-0.5 * x * x) / SQRT_2PI;
}

/**
 * Standard normal cdf via W. J. Cody's rational erfc approximation
 * (|error| < 1e-14 in double precision) — the textbook 7.1.26 polynomial's
 * 1.5e-7 is visible in a deep-OTM price.
 */
export function normCdf(x: number): number {
  return 0.5 * erfc(-x / Math.SQRT2);
}

function erfc(x: number): number {
  const z = Math.abs(x);
  let r: number;
  if (z < 0.5) {
    // erf series region
    const t = z * z;
    const p =
      3209.3775891384694 +
      t *
        (377.485237685302 +
          t * (113.86415415105016 + t * (3.1611237438705655 + t * 0.18577770618460315)));
    const qq =
      2844.236833439171 +
      t * (1282.6165260773723 + t * (244.02463793444417 + t * (23.601290952344122 + t)));
    const erf = (z * p) / qq;
    r = 1 - erf;
  } else if (z < 4) {
    const p =
      1230.3393547979972 +
      z *
        (2051.0783778260716 +
          z *
            (1712.0476126340707 +
              z *
                (881.952221241769 +
                  z *
                    (298.6351381974001 +
                      z *
                        (66.11919063714163 +
                          z *
                            (8.883149794388377 +
                              z * (0.5641884969886701 + z * 2.1531153547440383e-8)))))));
    const qq =
      1230.3393548037495 +
      z *
        (3439.3676741437216 +
          z *
            (4362.619090143247 +
              z *
                (3290.7992357334597 +
                  z *
                    (1621.3895745666903 +
                      z *
                        (537.1811018620099 +
                          z * (117.6939508913125 + z * (15.744926110709835 + z)))))));
    r = (Math.exp(-z * z) * p) / qq;
  } else {
    const t = 1 / (z * z);
    const p =
      0.0006587491615298378 +
      t *
        (0.016083785148742275 +
          t *
            (0.12578172611122926 +
              t * (0.36034489994980445 + t * (0.30532663496123236 + t * 0.016315387137302097))));
    const qq =
      0.0023352049762686918 +
      t *
        (0.06051834131244132 +
          t * (0.5279051029514285 + t * (1.8729528499234673 + t * (2.568520192289822 + t))));
    r = (Math.exp(-z * z) / z) * (1 / Math.sqrt(Math.PI) - (t * p) / qq);
  }
  return x < 0 ? 2 - r : r;
}

function d1d2(s: number, k: number, t: number, b: number, sigma: number): [number, number] {
  const vt = sigma * Math.sqrt(t);
  const d1 = (Math.log(s / k) + (b + 0.5 * sigma * sigma) * t) / vt;
  return [d1, d1 - vt];
}

/**
 * Generalised BSM with cost of carry b: b = r − q for stock (BSM), b = 0 for
 * a futures option (Black-76). One implementation, two thin entry points.
 */
function generalized(inp: BsInput, b: number): Greeks {
  const { s, k, t, r, sigma, right } = inp;
  if (!(s > 0 && k > 0)) throw new RangeError("s and k must be positive");
  // At or past expiry, or zero vol: intrinsic value, step delta.
  if (t <= 0 || sigma <= 0) {
    const intrinsic = right === "C" ? Math.max(s - k, 0) : Math.max(k - s, 0);
    const itm = right === "C" ? s > k : s < k;
    return {
      price: intrinsic,
      delta: itm ? (right === "C" ? 1 : -1) : 0,
      gamma: 0,
      theta: 0,
      vega: 0,
      rho: 0,
    };
  }
  const [d1, d2] = d1d2(s, k, t, b, sigma);
  const carry = Math.exp((b - r) * t); // e^{-qT} for BSM, e^{-rT} for Black-76
  const disc = Math.exp(-r * t);
  const sqrtT = Math.sqrt(t);
  const pdf1 = normPdf(d1);

  const gamma = (carry * pdf1) / (s * sigma * sqrtT);
  const vega = (s * carry * pdf1 * sqrtT) / 100;
  const decay = -(s * carry * pdf1 * sigma) / (2 * sqrtT);

  let price: number, delta: number, thetaYr: number, rho: number;
  if (right === "C") {
    const n1 = normCdf(d1);
    const n2 = normCdf(d2);
    price = s * carry * n1 - k * disc * n2;
    delta = carry * n1;
    thetaYr = decay - (b - r) * s * carry * n1 - r * k * disc * n2;
    rho = b === 0 ? (-t * price) / 100 : (k * t * disc * n2) / 100;
  } else {
    const n1 = normCdf(-d1);
    const n2 = normCdf(-d2);
    price = k * disc * n2 - s * carry * n1;
    delta = -carry * n1;
    thetaYr = decay + (b - r) * s * carry * n1 + r * k * disc * n2;
    rho = b === 0 ? (-t * price) / 100 : (-k * t * disc * n2) / 100;
  }
  return { price, delta, gamma, theta: thetaYr / 365, vega, rho };
}

/** Black-Scholes-Merton (stock / ETF / index options). */
export function bsm(inp: BsInput): Greeks {
  return generalized(inp, inp.r - (inp.q ?? 0));
}

/** Black-76 (options on futures): `s` is the futures price. */
export function black76(inp: BsInput): Greeks {
  return generalized(inp, 0);
}

/**
 * Implied volatility from an option price, or null when none exists (price
 * below intrinsic or above the no-arbitrage cap, or t ≤ 0). Newton steps from
 * the Brenner–Subrahmanyam guess, guarded by a bisection bracket so it never
 * diverges on a flat-vega wing.
 */
export function impliedVol(
  price: number,
  inp: Omit<BsInput, "sigma">,
  model: "bsm" | "black76" = "bsm",
): number | null {
  const { s, k, t, r, right } = inp;
  if (!(price > 0) || !(t > 0) || !(s > 0) || !(k > 0)) return null;
  const b = model === "black76" ? 0 : r - (inp.q ?? 0);
  const carry = Math.exp((b - r) * t);
  const disc = Math.exp(-r * t);
  const lower =
    right === "C" ? Math.max(s * carry - k * disc, 0) : Math.max(k * disc - s * carry, 0);
  const upper = right === "C" ? s * carry : k * disc;
  if (price <= lower + 1e-12 || price >= upper) return null;

  const f = (sigma: number) => generalized({ ...inp, sigma }, b);
  let lo = 1e-6;
  let hi = 10; // 1000% vol
  let sigma = Math.min(Math.max(Math.sqrt((2 * Math.PI) / t) * (price / s), 0.05), 3);
  for (let i = 0; i < 100; i++) {
    const g = f(sigma);
    const diff = g.price - price;
    if (Math.abs(diff) < 1e-10) return sigma;
    if (diff > 0) hi = sigma;
    else lo = sigma;
    const vegaRaw = g.vega * 100; // back to per-unit σ
    let next = vegaRaw > 1e-12 ? sigma - diff / vegaRaw : NaN;
    if (!(next > lo && next < hi)) next = 0.5 * (lo + hi);
    if (Math.abs(next - sigma) < 1e-12) return next;
    sigma = next;
  }
  return sigma;
}
