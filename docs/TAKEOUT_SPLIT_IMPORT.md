# Google Pay group-expense import (dev)

Available in dev version `0.4.1-dev-takeout`, code `102`. QA/prod are unchanged.

## Import on the phone

1. Settings -> Import History -> Choose Transaction / Group Expenses File.
2. Select Google Takeout's `Group expenses.json` (a JSON file containing `Group_expenses`).
3. Search/select the exact participant name that identifies you. No owner name is guessed.
4. Open the preview. Check group, counterparty, amount, direction and pending/settled status. Review each page as needed.
5. Confirm that the selected identity and creator-based directions are correct, then confirm Import Selected Splits.

Nothing is written during owner selection or preview. Cancel leaves the financial database unchanged. The raw file is held only in memory, is not copied into the repository, and must be selected again if Android recreates the import session.

## Mapping rules

- The export's `creator` is treated as the person requesting repayment. This assumption is disclosed and requires user confirmation in the preview.
- If you are the creator, other named participants' item shares become `they_owe_you`; your own item is excluded.
- If someone else is creator, only your named item becomes `you_owe_them` to that creator. Other participants' items are excluded.
- `UNPAID` becomes pending. `PAID_RECEIVED` and `MARK_AS_PAID` become settled history. No transaction, income or expense is created.
- Closed unpaid requests, completed requests with unpaid shares, failed/unknown states, blank/ambiguous participant names, and invalid fields are skipped with reason counts. Skipped records are not silently treated as debt or queued as completed payments; inspect the original export for manual resolution.
- The source creation timestamp is preserved. The export does not supply settlement timestamps, so the importer does not invent a settlement date.
- Case/spacing-normalized group/name values are used for stable import identity. Original display names are retained.

## Duplicate and correction protection

Stable UUIDs are derived from the owner, creation timestamp, creator, normalized group/title, and participant. Amount/status and item ordering do not change the identity. Re-imported existing IDs are kept unchanged, including manual corrections, local settled status and deletion tombstones. Re-import does not update older pending records to a newer export state automatically.

Likely overlaps with notification/manual splits (matching counterparty, direction, amount and group or description) are unselected by default. The user can review and explicitly select them; no uncertain automatic merge occurs. Imports with a different owner are refused when existing Takeout split records identify another account.

Selected new shares are written in one Room transaction. A failed write rolls back all additions in that operation. Shared opens on Pending and limits rendered rows to 50 at a time, with Show More and settled-history filters.

## Validation

The supplied local export's structure was inspected without copying it into Git or logging participant names, titles, references or financial amounts. It has 301 expenses, valid ISO creation timestamps, and the expected creator/group/title/items/state/amount fields. Test fixtures use synthetic people and values.

Device checks cover both directions, own-share exclusion, paid/manual-paid history, failed/blank/ambiguous/closed-unpaid exclusions, stable re-import identities, preserved corrections/deletions, zero transaction creation, transactional rollback, and the owner/preview/cancel flow. Standard dev unit tests and lint are also used.

Once confirmed, parsed split records follow the existing optional dev sync behavior. Raw export/review content is not uploaded by this flow. The local preview explicitly explains that parsed records may sync if dev cloud sync is enabled.
