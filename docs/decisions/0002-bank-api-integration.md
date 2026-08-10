# 0002 — Bank API integration (the "middle man" question)

> **Status:** Proposal. Not implemented.
> **Author:** research pass, 2026-08
> **Goal:** Decide whether the receipt tracker should pull bank transactions from a real aggregator (the obvious candidate being Plaid) instead of relying on manual entry.

## 1. Context

The app is local-first end to end: ML Kit OCR runs on-device, Room stores everything under `/data/data/com.example.receipttracker/`, the `AndroidManifest.xml` declares no `INTERNET` permission, and bank transactions are entered by hand in `AddTransactionActivity` then matched against receipts in `MatchActivity`. The friction is real — the user types every bank charge into the app. The question is whether integrating a bank aggregator (the de-facto "middle man" in the US is Plaid, with Finicity / MX / Yodlee / Akoya as the realistic alternatives) is worth the effort, the cost, the privacy shift, and the architectural lift of adding a network stack to a previously offline app.

## 2. Provider landscape

All four major US aggregators are roughly comparable on institution coverage. The differences are in developer experience, pricing model, and sales motion. For a single-developer, US-only, personal-finance app, the realistic shortlist is two: **Plaid** and **Finicity** (and only Plaid if you want a self-serve sign-up without a sales call).

| Provider | Pricing model | Free tier | Sandbox | Android SDK | Data scope | Sales motion |
|---|---|---|---|---|---|---|
| **Plaid** | One-time per connected account (Auth, Identity, Income) + monthly subscription (Transactions, Investments, Liabilities) + per-call (Balance, /transactions/refresh, Signal) | **Limited Production**: 200 free live API calls per product; Sandbox always free | Self-serve, GA-quality | First-class — `com.plaid.link:sdk-core` on Maven Central, current 5.x (Kotlin 1.9.25, target SDK 35); v6.0.0 (session-based, ~1.2 MB) shipped 2026 | 11,000+ US FIs, 24 months transaction history, OAuth coverage on most large US banks | Self-serve dashboard, sales for production-scale contracts |
| **Finicity (Mastercard)** | Per-event, volume tiers | Sandbox; production is sales-led | Self-serve | No first-party Android SDK (REST only, build your own Link equivalent) | 15,000+ US FIs, 95% US deposit coverage | Sales-led, no public rate card |
| **MX** | Volume-tiered, contract | Free developer tier (data enrichment-focused) | Self-serve | No first-party Android SDK | 16,000+ US FIs, strong categorisation | Sales-led for production |
| **Envestnet Yodlee** | Subscription + per-user, $5K–$15K/mo platform fee | None | Registration required | No first-party Android SDK | 17,000+ data sources (incl. intl) | Sales-led, enterprise |
| **Akoya** | Tiered; custom for 10K+ connections/mo | Sandbox; free self-serve | Registration required | No first-party Android SDK | ~750 FIs (bank-owned consortium, 100% API-direct, no scraping) | Sales-led |
| **Stripe Financial Connections** | $0.10/balance, $1.50/verification | Stripe account → immediate | Immediate | First-class if you use Stripe SDK | Wide US coverage | Self-serve; only worth it if you're on Stripe already |

Sources: [Plaid pricing](https://plaid.com/pricing/), [Plaid billing docs](https://plaid.com/docs/account/billing/), [Plaid pricing models explained](https://support.plaid.com/hc/en-us/articles/16194632655895-How-much-does-Plaid-cost-and-what-are-the-pricing-models), [Plaid Link Android SDK](https://plaid.com/docs/link/android/), [Plaid Android SDK releases](https://github.com/plaid/plaid-link-android/releases), [Plaid changelog](https://plaid.com/docs/changelog/), [Plaid vs MX vs Finicity (FintechSpecs)](https://fintechspecs.com/blog/plaid-alternatives-bank-data-connectivity/), [Plaid vs MX vs Finicity 2026 (Atlas Forge)](https://atlasforgefinancial.com/blog/plaid-vs-mx-vs-finicity-2026), [Plaid vs Yodlee pricing (Monetizely)](https://www.getmonetizely.com/articles/plaid-vs-yodlee-how-much-will-financial-data-apis-cost-your-fintech-in-2025).

**The only realistic option for a one-person US app is Plaid.** It is the only provider with a maintained Android SDK, a self-serve sandbox, and a documented per-developer-account sign-up flow. Every other option either lacks an Android SDK (forcing you to build your own WebView OAuth flow on top of their REST API) or requires a sales call before you can write a line of code.

## 3. Integration path (Plaid, end to end)

1. **Sign up for a Plaid developer account** at the [Plaid Dashboard](https://dashboard.plaid.com). Get a `client_id` and `sandbox` `secret`. Add the app's package name (`com.example.receipttracker`) to the **Allowed Android package names** list under Developers → API.
2. **Add `com.plaid.link:sdk-core` to `app/build.gradle`** (current 5.x line, minSdk 21; v6.0.0 raises minSdk to 26 — the app's current minSdk is 26 so v6 is fine when you adopt it). The SDK is the **Android-native Link UI** that drops the user into a credential-entry / OAuth flow and returns to your app when done. Both Java and Kotlin are supported; the [example app](https://github.com/plaid/plaid-link-android) ships both.
3. **Add `<uses-permission android:name="android.permission.INTERNET" />` to the manifest.** This is a one-way door for the local-first posture — every reviewer will need to remember the app is no longer zero-network. Consider also `ACCESS_NETWORK_STATE` for a "no connection" toast.
4. **Stand up a tiny server for the `link_token` exchange.** Plaid explicitly says [`/link/token/create` must not be called from the mobile client](https://plaid.com/docs/link/android/) — the `client_secret` cannot ship in the APK. The shape of the server is two endpoints:
   - `POST /plaid/link-token` → calls `POST https://production.plaid.com/link/token/create` with the user's `client_user_id` + `android_package_name`, returns the `link_token` to the app.
   - `POST /plaid/exchange` → receives the `public_token` from the app, calls `/item/public_token/exchange`, persists the `access_token` (or returns it to the app if you trust device storage), returns 200.
   This server can be a 50-line [Cloudflare Worker](https://workers.cloudflare.com) on the free tier, a Firebase Cloud Function, or a $5/mo VPS. The app does the rest of the Plaid calls (`/transactions/sync`, etc.) using the `access_token` — those are not required to come from a server, and pulling them from the device keeps the "no third party sees your raw transactions" promise mostly intact.
5. **OAuth flow for the bank login.** Most large US banks (Chase, BoA, Wells, Capital One) now require OAuth, not credential scraping. Plaid Link Android handles the bank redirect / return-to-app dance for you via [Android App Links](https://developer.android.com/training/app-links) (you ship a `.well-known/assetlinks.json` from your domain and add an `<intent-filter android:autoVerify="true">` to `MainActivity`). The user logs into their bank in the bank-branded webview, Plaid captures the consent, control returns to your app with the `public_token` in `LinkSuccess.publicToken`. [Plaid's OAuth guide](https://plaid.com/docs/link/oauth/) is the canonical reference.
6. **Persist the `access_token` per linked Item.** Add a new `linked_account` table (or piggyback on `bank_transactions` with an `access_token` column) to Room. Encrypt the token at rest with `EncryptedSharedPreferences` or the Android Keystore — it's a long-lived bearer credential and exfiltrating it gives anyone with the token read access to that bank account.
7. **Fetch transactions with [`/transactions/sync`](https://plaid.com/docs/api/products/transactions/).** Plaid's recommended replacement for the old `/transactions/get`. Cursor-based — call it with no cursor on first run, then keep the returned `next_cursor` and re-call with that cursor next time. Yields `added`, `modified`, `removed` lists, so you can patch your local DB incrementally. Plaid checks for new transactions 1–4×/day per institution and fires a `SYNC_UPDATES_AVAILABLE` webhook when there are changes — webhooks require your server to have an HTTPS endpoint, so for the no-server variant you just poll on app open (and on a daily `WorkManager` job).
8. **Map Plaid transactions → `BankTransaction` rows.** Same shape: merchant, amount, date, optional account. Plaid provides a `personal_finance_category` (`FOOD_AND_DRINK`, `TRAVEL`, etc.) which is a near-perfect fit for the app's `merchants.json` category field. The `matchGroupId` UUID the app already uses for receipt ↔ bank-tx pairing continues to work unchanged.
9. **Dedupe on ingest.** The single biggest correctness concern: never insert a `BankTransaction` the user already entered by hand. Key the insert on `(plaid_transaction_id)` — store the Plaid ID in a new column and treat duplicate `(plaid_transaction_id, account_id)` as "this is the same charge, skip" or "merge with the existing manual row, clear the manual marker."
10. **Run on a `WorkManager` periodic job** (every 6–12 hours) plus on app open. No foreground service, no always-running daemon — the app is local-first and should stay that way; pull on demand.

## 4. Effort estimate

For one developer working in spare time, half a day per coding session on average. "Day" here = 3–4 focused hours.

| Phase | What | Estimate (days) |
|---|---|---|
| **0. Server** | Stand up a Cloudflare Worker / Firebase Function for `/link-token` and `/exchange`; decide on token storage (server-held vs returned to app); HTTPS + App-Links assetlinks.json | 1.5 |
| **1. Plaid Link + sandbox** | Add SDK to `build.gradle`, register package name in Plaid dashboard, INTERNET perm, `LinkTokenActivity` that opens Link, handles `LinkSuccess`/`LinkExit`, persists `access_token` (encrypted), re-auth flow when an Item's login expires | 4 |
| **2. Transactions ingest** | New Room column for `plaid_transaction_id`, `linkedAccount` table, `/transactions/sync` loop with cursor persistence, `WorkManager` daily job + on-app-open pull, merchant/amount/date mapping | 3 |
| **3. Dedup + match** | Skip-or-merge logic for manual `BankTransaction` rows that show up in Plaid, surface merged/unmerged items in `MatchActivity` so the user can override, ensure `matchGroupId` is preserved across the merge | 2 |
| **4. Reconcile, error handling, edge cases** | Item re-link prompt when `ITEM_LOGIN_REQUIRED` / `PENDING_EXPIRATION` webhook fires, multi-account UI (which account is this charge from?), `access_token` rotation, OAuth bank App Links test across the big 4 US banks, category propagation, account unlink UI | 4 |
| **5. Ship-ready** | Test coverage, in-app log entries for the new code paths, update `README.md` and `ARCHITECTURE.md`, update `AndroidManifest.xml` privacy section, CHANGELOG entry | 2 |
| **Total** | | **~16.5 days** (~2 months at the user's typical pace) |

The 4 days for Phase 1 is realistic and not padded: the Android SDK does the heavy lifting (UI, MFA, OAuth), but wiring `access_token` storage, the re-auth flow, and the encrypted persistence right takes a real session. Phase 4 is the one most likely to slip — bank-specific OAuth behaviour has historically been where Plaid integrations spend the most debugging time.

## 5. Cost estimate

Plaid's current model (as of August 2026) is three pricing models in one product: [one-time, subscription, per-request](https://plaid.com/docs/account/billing/). For the only products this app needs (`transactions` to ingest charges, `auth` only if you ever want to verify a routing/account number — not needed for read-only), the bill is:

- **Plaid `Transactions`** — **subscription**, billed monthly per connected Item (a single login at a single bank, regardless of how many accounts are under it). Current publicly-disclosed range: roughly **$0.30 per Item per month** at the lowest published tier; production rates negotiated.
- **`/transactions/refresh`** — **per-request**, ~$0.30 per call. Optional, only used if you want on-demand sync.
- **`/accounts/balance/get`** — **per-request**, ~$0.10 per call. Not needed by this app.
- **`Auth`** — **one-time per Item**, ~$1.20. Not needed for read-only transactions.
- **Sandbox** — free, unlimited.
- **Limited Production** — [200 free live API calls per product, once per developer](https://plaid.com/pricing/). Enough to do all the live-bank end-to-end testing for this app and never pay a cent if usage stays below 200 calls per product.

**For a single user with ~3 linked bank accounts and ~200 transactions/month**, the bill is dominated by the Transactions subscription: **3 Items × $0.30/mo = $0.90/month** (~$11/year), plus a one-time $1.20/Item setup charge on the very first link. If you re-link an Item (bank password changed, OAuth consent expired) the monthly fee restarts. Webhooks are free. Storage is local (free). Bandwidth is trivial.

For a public release with N users, multiply by N and the rate stays in the same range until 10K+ connected Items, where volume discounts kick in ([Vendr marketplace data](https://www.vendr.com/marketplace/plaid) pegs the median Plaid buyer at ~$9.6K/year, which implies mid-four-figure users, not relevant at hobby scale).

**One non-obvious cost: Production onboarding.** Moving from Limited Production to full Production requires [registering a legal entity (your own name is fine), filling a security questionnaire, and signing an MSA](https://news.ycombinator.com/item?id=37614748). The first three or four times you submit a Production request with a personal-use rationale, you get approved automatically within a day or two; the questionnaire is calibrated for solo hobbyists, not just enterprises. There is no monetary cost, but it is paperwork the project doesn't have today.

## 6. Privacy & data model implications

The app's [README](https://github.com/nicholashutter/android-receipt-tracker) and `AndroidManifest.xml` today say "no network permissions are declared, no analytics SDK, no third-party network calls" and the [local-first section](README.md#privacy--local-first) leans on that as a feature. Adding Plaid changes the posture in three concrete ways:

1. **The bank now sees a third party.** The OAuth grant the user gives to their bank goes bank → Plaid → your app. Plaid is the API provider; this is the architectural point of the abstraction, and it is unavoidable. If the user has trust concerns about Plaid specifically, no Plaid integration will satisfy them.
2. **The app gets a copy of every transaction.** Once you call `/transactions/sync`, the full transaction history (up to 24 months per [Plaid's docs](https://plaid.com/docs/api/products/transactions/)) lives in the app's Room DB. That's the same storage the receipts live in, and it remains on-device and app-private. JSON export, if you keep it, will now include bank transactions too — and you should re-examine whether you want to keep bank transactions in the same export.
3. **You get to add a "fetch once, then offline" mode.** Architecturally, the cleanest privacy story is: when the user opens the app, sync transactions from Plaid; on subsequent opens, use the local copy; never re-sync unless the user explicitly taps "Refresh." Plaid has no insight into how often you read the local copy, and the access_token only needs to be used to talk to Plaid during a sync. The local-first promise is mostly preserved — the app is offline-first for reads, online-only for the initial / periodic ingestion.

`matchGroupId` and the `BankTransaction` schema don't need to change. The only new column on `bank_transactions` is `plaid_transaction_id` (nullable, indexed, so manual entries and Plaid-sourced rows can coexist in the same table).

## 7. Risks and unknowns

- **Plaid pricing has changed at least twice in five years** (per-link → pay-as-you-go; the major 2024/2025 restructuring, then the JPMorgan 2025 data-fee renegotiation reported to cost Plaid ~$300M/year). It will change again, and the published rate sheet is not a contract. Build a config screen that surfaces "active Items" and lets the user unlink — if the monthly fee structure moves, you want the unlink to be one tap.
- **Small credit unions and a handful of regional banks are not on any aggregator.** Coverage of 11K+ US institutions sounds complete, but if the user happens to bank at a tiny credit union, they get nothing and the app degrades gracefully back to the manual entry that already exists.
- **Cash transactions never appear in Plaid.** Venmo/Cash App/PayPal cash equivalents do, but cash-in-hand (the landlord check, the coffee in cash) never will. The app must keep the manual-entry path forever, not delete it.
- **OAuth consent expires.** Banks vary; Chase consents often last 90–180 days, some go a year. The app needs a clear "your Wells Fargo connection needs to be renewed" prompt, not a silent failure on `/transactions/sync`. This is the single most common support ticket for any Plaid integration.
- **The server dependency.** Even if it's a 50-line Cloudflare Worker, it is a new piece of infrastructure to maintain. It will need Plaid's `client_secret` (so a secrets manager), an HTTPS endpoint (so a domain), and an uptime story. This is the first time the project touches a server.
- **Limited Production is enough for a single user indefinitely** ([200 free calls per product, no time limit on what the calls can do](https://plaid.com/pricing/)). But the moment the app goes public and a second user links an account, you're effectively in real Production with a real bill.

## 8. Recommendation

**Skip it for now. Defer the decision until the app has shipped its current feature set (the soft-delete workflow, the budget progress card, the export pipeline) and a few external users have actually used the manual entry path enough to know whether bank-API integration is the next friction point or a solution to a non-problem.** Reasoning:

1. **The cost of the wrong call is asymmetric.** If you build Plaid integration now and it turns out the user prefers manual entry (faster than opening the bank app + the Plaid OAuth dance + waiting for the sync), you spent 16+ days and added a server for nothing. If you skip and later decide you need it, the SDK and APIs are stable enough that the same estimate still applies — there's no Plaid feature being deprecating that you'd miss by waiting a quarter.
2. **The local-first promise is a real feature, not just marketing.** "No network permissions" is a meaningful privacy signal for the kind of user who installs a receipt tracker. Adding INTERNET to the manifest for a feature that touches the bank account — the most sensitive data on the phone — should be a deliberate, post-PMF decision, not a "while we're at it."
3. **The friction this fixes is not the biggest friction in the app today.** Receipt scanning already does the heavy OCR + verification lift. The bank-transaction step is the *last* entry in the flow, not the first. Optimising the bottom of the funnel when the top of the funnel is still single-user and pre-launch is premature.
4. **The server requirement is the real cost.** The SDK and the integration are well-trodden ground. The Cloudflare Worker, the secrets management, the App-Links assetlinks.json, the OAuth bank-by-bank test matrix — that is real, ongoing work that compounds. Solo-developer projects die under the weight of one too many "and also I need a server now" obligations.

**When to revisit:** when the app has 5+ active external users, the budget feature is shipping, and the user has personally typed in 200+ bank transactions and found it annoying enough to want a better path. At that point the cost/benefit calculation flips, and the integration can be scoped as a focused 2-week build rather than a leap into a new architecture.
