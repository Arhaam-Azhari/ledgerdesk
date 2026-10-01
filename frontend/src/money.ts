// Amounts arrive as decimal strings. Keep arithmetic in cents instead of floating point.
export function cents(value: string): bigint {
  const raw = String(value);
  if (!/^-?\d+(\.\d{1,2})?$/.test(raw))
    throw new Error("Invalid monetary value.");
  const negative = raw.startsWith("-");
  const [whole, fraction = ""] = raw.replace(/^-/, "").split(".");
  const amount = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  return negative ? -amount : amount;
}
export function dollars(value: bigint): string {
  const n = value < 0n ? -value : value;
  return `${value < 0n ? "-" : ""}$${(n / 100n).toLocaleString("en-US")}.${String(n % 100n).padStart(2, "0")}`;
}
export const today = () => new Date().toLocaleDateString("en-CA");
