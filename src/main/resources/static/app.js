"use strict";

/**
 * LedgerGuard demonstration UI.
 *
 * A plain API client only: every action below calls the existing
 * same-origin REST API with fetch() and relative URLs. Nothing here talks
 * to PostgreSQL, Kafka, or any repository/service directly, and nothing
 * here computes or mutates a financial value locally — every displayed
 * amount/balance/count is exactly what the backend returned.
 *
 * All backend-derived content is rendered with textContent (never
 * innerHTML), so nothing returned by the API — including a settlement
 * CSV's own field values that might appear inside an error message — can
 * ever be interpreted as markup.
 */

(function () {
	const REQUEST_TIMEOUT_MS = 15000;
	const MAX_RECENT_IDS = 10;

	const STORAGE_KEYS = {
		accounts: "ledgerguard.recentAccountIds",
		imports: "ledgerguard.recentImportIds"
	};

	// ------------------------------------------------------------------
	// Task 17: authentication state. The access token lives ONLY in this
	// module-level variable -- never localStorage, sessionStorage, a URL,
	// a log line, or the "developer details" panel's raw request headers.
	// Reloading the page always signs the user out, by design.
	// ------------------------------------------------------------------

	let currentToken = null;
	let currentIdentity = null; // { username, role } decoded for display only

	// ------------------------------------------------------------------
	// centralized fetch + response handling
	// ------------------------------------------------------------------

	/**
	 * Issues one request and normalizes the outcome, regardless of
	 * whether it succeeded, returned a non-JSON body, or never reached
	 * the network at all. Never throws — every caller gets back a plain
	 * result object it can render directly. Automatically attaches the
	 * in-memory bearer token, if one is held, to every request.
	 */
	async function apiRequest(method, path, { headers, body, isFormData } = {}) {
		const controller = new AbortController();
		const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

		const init = {
			method,
			headers: Object.assign({}, headers),
			signal: controller.signal
		};
		if (currentToken) {
			init.headers["Authorization"] = `Bearer ${currentToken}`;
		}
		if (body !== undefined) {
			if (isFormData) {
				init.body = body;
			} else {
				init.headers["Content-Type"] = "application/json";
				init.body = JSON.stringify(body);
			}
		}

		let response;
		try {
			response = await fetch(path, init);
		} catch (err) {
			clearTimeout(timer);
			const timedOut = err && err.name === "AbortError";
			return {
				method,
				path,
				networkError: true,
				status: 0,
				statusText: timedOut ? "Request timed out" : "Network error — backend unavailable",
				ok: false,
				body: null
			};
		}
		clearTimeout(timer);

		const contentType = response.headers.get("content-type") || "";
		let parsedBody = null;
		if (contentType.indexOf("application/json") !== -1) {
			try {
				parsedBody = await response.json();
			} catch (err) {
				parsedBody = null;
			}
		} else {
			// Never render arbitrary non-JSON response text as HTML; just
			// drain the body so the connection can be reused.
			try {
				await response.text();
			} catch (err) {
				/* ignore */
			}
		}

		return {
			method,
			path,
			networkError: false,
			status: response.status,
			statusText: response.statusText,
			ok: response.ok,
			body: parsedBody
		};
	}

	function apiGet(path) {
		return apiRequest("GET", path);
	}

	function apiPostJson(path, body, extraHeaders) {
		return apiRequest("POST", path, { body, headers: extraHeaders });
	}

	function apiPostForm(path, formData) {
		return apiRequest("POST", path, { body: formData, isFormData: true });
	}

	// ------------------------------------------------------------------
	// small DOM helpers (textContent only — never innerHTML with
	// backend-derived content)
	// ------------------------------------------------------------------

	function byId(id) {
		return document.getElementById(id);
	}

	function clearChildren(node) {
		while (node.firstChild) {
			node.removeChild(node.firstChild);
		}
	}

	function appendKeyValue(dl, key, value) {
		const dt = document.createElement("dt");
		dt.textContent = key;
		const dd = document.createElement("dd");
		dd.textContent = value === undefined || value === null || value === "" ? "—" : String(value);
		dl.appendChild(dt);
		dl.appendChild(dd);
	}

	function renderKeyValueList(container, entries) {
		clearChildren(container);
		entries.forEach(([key, value]) => appendKeyValue(container, key, value));
	}

	function setDisabled(elements, disabled) {
		elements.forEach((el) => {
			if (el) {
				el.disabled = disabled;
			}
		});
	}

	function setButtonBusy(button, busy, busyLabel, idleLabel) {
		if (!button) {
			return;
		}
		button.disabled = busy;
		button.textContent = busy ? busyLabel : idleLabel;
	}

	/**
	 * Renders the shared "developer details" panel for one action: HTTP
	 * method, request path, HTTP status, and the raw response JSON (or a
	 * safe placeholder for a non-JSON/network-error response). Always via
	 * textContent — the response JSON is only ever pretty-printed inside a
	 * <pre> element's textContent, never parsed as HTML.
	 */
	function renderDevDetails(container, result) {
		clearChildren(container);

		const summary = document.createElement("dl");
		summary.className = "kv-list";
		appendKeyValue(summary, "Method", result.method);
		appendKeyValue(summary, "Path", result.path);
		appendKeyValue(summary, "Status", result.networkError ? "(no response)" : `${result.status} ${result.statusText}`);
		container.appendChild(summary);

		const pre = document.createElement("pre");
		if (result.networkError) {
			pre.textContent = result.statusText;
		} else if (result.body !== null && result.body !== undefined) {
			pre.textContent = JSON.stringify(result.body, null, 2);
		} else {
			pre.textContent = "(no JSON response body)";
		}
		container.appendChild(pre);
	}

	function describeApiError(result) {
		if (result.networkError) {
			return result.statusText;
		}
		if (result.body && typeof result.body.message === "string") {
			return result.body.message;
		}
		return `Request failed with status ${result.status}`;
	}

	// ------------------------------------------------------------------
	// localStorage: recent ids only (never balances, CSV content, or full
	// API responses)
	// ------------------------------------------------------------------

	function readRecentIds(key) {
		try {
			const raw = window.localStorage.getItem(key);
			const parsed = raw ? JSON.parse(raw) : [];
			return Array.isArray(parsed) ? parsed : [];
		} catch (err) {
			return [];
		}
	}

	function writeRecentIds(key, ids) {
		try {
			window.localStorage.setItem(key, JSON.stringify(ids.slice(0, MAX_RECENT_IDS)));
		} catch (err) {
			/* localStorage unavailable (private browsing, quota) — degrade silently */
		}
	}

	function addRecentId(key, id) {
		if (!id) {
			return;
		}
		const existing = readRecentIds(key).filter((existingId) => existingId !== id);
		existing.unshift(id);
		writeRecentIds(key, existing);
		refreshRecentIdViews();
	}

	function populateDatalist(datalistId, ids) {
		const datalist = byId(datalistId);
		if (!datalist) {
			return;
		}
		clearChildren(datalist);
		ids.forEach((id) => {
			const option = document.createElement("option");
			option.value = id;
			datalist.appendChild(option);
		});
	}

	function populateRecentList(spanId, ids) {
		const span = byId(spanId);
		if (!span) {
			return;
		}
		clearChildren(span);
		if (ids.length === 0) {
			span.textContent = "none yet";
			return;
		}
		span.textContent = ids.join(", ");
	}

	function refreshRecentIdViews() {
		const accountIds = readRecentIds(STORAGE_KEYS.accounts);
		const importIds = readRecentIds(STORAGE_KEYS.imports);
		populateDatalist("recent-account-ids", accountIds);
		populateDatalist("recent-import-ids", importIds);
		populateRecentList("recent-accounts-list", accountIds);
		populateRecentList("recent-imports-list", importIds);
	}

	// ------------------------------------------------------------------
	// 0. sign in / sign out
	// ------------------------------------------------------------------

	/**
	 * Decodes a JWT's payload claims for DISPLAY ONLY -- no signature
	 * verification is performed or implied client-side; the server always
	 * re-validates the token on every request. Never throws; returns null
	 * on any malformed input.
	 */
	function decodeJwtPayloadForDisplay(token) {
		try {
			const parts = token.split(".");
			if (parts.length !== 3) {
				return null;
			}
			const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
			const padded = base64 + "===".slice((base64.length + 3) % 4);
			return JSON.parse(window.atob(padded));
		} catch (err) {
			return null;
		}
	}

	function renderIdentity() {
		const banner = byId("auth-identity");
		const signInForm = byId("sign-in-form");
		const signOutButton = byId("sign-out-button");
		if (currentIdentity) {
			banner.textContent = `Signed in as ${currentIdentity.username} (${currentIdentity.role})`;
			banner.hidden = false;
			signInForm.hidden = true;
			signOutButton.hidden = false;
		} else {
			banner.textContent = "";
			banner.hidden = true;
			signInForm.hidden = false;
			signOutButton.hidden = true;
		}
	}

	function signOut() {
		currentToken = null;
		currentIdentity = null;
		renderIdentity();
		byId("sign-in-status").textContent = "Signed out.";
	}

	function initAuth() {
		const form = byId("sign-in-form");
		const submitButton = form.querySelector("button[type=submit]");
		const status = byId("sign-in-status");

		form.addEventListener("submit", async (event) => {
			event.preventDefault();
			setButtonBusy(submitButton, true, "Signing in…", "Sign in");
			status.textContent = "";

			const username = byId("sign-in-username").value.trim();
			const password = byId("sign-in-password").value;

			const result = await apiPostJson("/api/v1/auth/token", { username, password });
			renderDevDetails(byId("sign-in-dev-details"), result);

			if (result.ok && result.body && result.body.accessToken) {
				currentToken = result.body.accessToken;
				const claims = decodeJwtPayloadForDisplay(currentToken);
				const role = claims && Array.isArray(claims.roles) ? claims.roles[0] : "unknown role";
				currentIdentity = { username: (claims && claims.sub) || username, role };
				byId("sign-in-password").value = "";
				renderIdentity();
			} else {
				currentToken = null;
				currentIdentity = null;
				status.textContent = describeApiError(result);
				renderIdentity();
			}
			setButtonBusy(submitButton, false, "Signing in…", "Sign in");
		});

		byId("sign-out-button").addEventListener("click", signOut);
		renderIdentity();
	}

	// ------------------------------------------------------------------
	// 1. backend status
	// ------------------------------------------------------------------

	async function refreshStatus() {
		const indicator = byId("status-indicator");
		const checkedAt = byId("status-checked-at");
		const button = byId("status-refresh");
		setButtonBusy(button, true, "Checking…", "Refresh");

		const result = await apiGet("/actuator/health");
		renderDevDetails(byId("status-dev-details"), result);

		indicator.classList.remove("status-unknown", "status-up", "status-down");
		if (result.networkError) {
			indicator.classList.add("status-down");
			indicator.textContent = "Backend unavailable";
		} else if (result.ok && result.body && result.body.status === "UP") {
			indicator.classList.add("status-up");
			indicator.textContent = "UP";
		} else if (result.body && typeof result.body.status === "string") {
			indicator.classList.add("status-down");
			indicator.textContent = result.body.status;
		} else {
			indicator.classList.add("status-down");
			indicator.textContent = `HTTP ${result.status}`;
		}
		checkedAt.textContent = `Last checked: ${new Date().toLocaleTimeString()}`;

		setButtonBusy(button, false, "Checking…", "Refresh");
	}

	// ------------------------------------------------------------------
	// 2. create account
	// ------------------------------------------------------------------

	function initCreateAccount() {
		const form = byId("create-account-form");
		const resultBox = byId("create-account-result");
		const fields = byId("create-account-fields");
		const copyButton = byId("create-account-copy");
		const submitButton = form.querySelector("button[type=submit]");
		let lastAccountId = null;

		form.addEventListener("submit", async (event) => {
			event.preventDefault();
			setButtonBusy(submitButton, true, "Creating…", "Create account");
			resultBox.hidden = true;
			copyButton.hidden = true;

			const ownerName = byId("create-account-owner").value.trim();
			const currency = byId("create-account-currency").value.trim().toUpperCase();

			const result = await apiPostJson("/api/v1/accounts", { ownerName, currency });
			renderDevDetails(byId("create-account-dev-details"), result);

			if (result.ok && result.body) {
				lastAccountId = result.body.id;
				renderKeyValueList(fields, [
					["Account ID", result.body.id],
					["Owner name", result.body.ownerName],
					["Currency", result.body.currency],
					["Balance", result.body.balance],
					["Created at", result.body.createdAt]
				]);
				resultBox.hidden = false;
				copyButton.hidden = !lastAccountId;
				addRecentId(STORAGE_KEYS.accounts, lastAccountId);
			} else {
				lastAccountId = null;
				renderKeyValueList(fields, [["Error", describeApiError(result)]]);
				resultBox.hidden = false;
			}

			setButtonBusy(submitButton, false, "Creating…", "Create account");
		});

		copyButton.addEventListener("click", () => copyToClipboard(lastAccountId, copyButton, "Copy account ID"));
	}

	async function copyToClipboard(value, button, idleLabel) {
		if (!value) {
			return;
		}
		try {
			await navigator.clipboard.writeText(value);
			button.textContent = "Copied!";
		} catch (err) {
			button.textContent = "Copy failed";
		}
		window.setTimeout(() => {
			button.textContent = idleLabel;
		}, 1500);
	}

	// ------------------------------------------------------------------
	// 3. account lookup
	// ------------------------------------------------------------------

	function initAccountLookup() {
		const idInput = byId("lookup-account-id");

		byId("lookup-balance-button").addEventListener("click", async () => {
			const accountId = idInput.value.trim();
			const button = byId("lookup-balance-button");
			const resultBox = byId("lookup-balance-result");
			const fields = byId("lookup-balance-fields");
			if (!accountId) {
				renderKeyValueList(fields, [["Error", "Enter an account ID first."]]);
				resultBox.hidden = false;
				return;
			}
			setButtonBusy(button, true, "Loading…", "Load balance");

			const result = await apiGet(`/api/v1/accounts/${encodeURIComponent(accountId)}/balance`);
			renderDevDetails(byId("lookup-balance-dev-details"), result);

			if (result.ok && result.body) {
				renderKeyValueList(fields, [
					["Account ID", result.body.accountId],
					["Balance", result.body.balance],
					["Currency", result.body.currency]
				]);
			} else {
				renderKeyValueList(fields, [["Error", describeApiError(result)]]);
			}
			resultBox.hidden = false;
			setButtonBusy(button, false, "Loading…", "Load balance");
		});

		byId("lookup-history-button").addEventListener("click", async () => {
			const accountId = idInput.value.trim();
			const button = byId("lookup-history-button");
			const resultContainer = byId("lookup-history-result");
			const body = byId("lookup-history-body");
			const emptyState = byId("lookup-history-empty");
			const summary = byId("lookup-history-summary");
			clearChildren(body);
			emptyState.hidden = true;
			summary.textContent = "";

			if (!accountId) {
				renderDevDetails(byId("lookup-history-dev-details"), {
					method: "GET", path: "", networkError: true, status: 0,
					statusText: "Enter an account ID first.", ok: false, body: null
				});
				resultContainer.hidden = false;
				return;
			}

			const page = parsePageParam(byId("lookup-page").value, 0);
			const size = parsePageParam(byId("lookup-size").value, 20);
			const params = new URLSearchParams({ page: String(page), size: String(size) });
			setButtonBusy(button, true, "Loading…", "Load transactions");

			const path = `/api/v1/accounts/${encodeURIComponent(accountId)}/transactions?${params.toString()}`;
			const result = await apiGet(path);
			renderDevDetails(byId("lookup-history-dev-details"), result);

			if (result.ok && result.body) {
				const content = Array.isArray(result.body.content) ? result.body.content : [];
				if (content.length === 0) {
					emptyState.hidden = false;
				} else {
					content.forEach((item) => {
						const row = document.createElement("tr");
						appendCell(row, item.transactionId);
						appendCell(row, item.entryType);
						appendCell(row, item.amount);
						appendCell(row, item.currency);
						appendCell(row, item.createdAt);
						body.appendChild(row);
					});
				}
				summary.textContent = `Page ${result.body.page} of ${result.body.totalPages} · ${result.body.totalElements} total entries`;
			} else {
				emptyState.hidden = false;
				emptyState.textContent = describeApiError(result);
			}
			resultContainer.hidden = false;
			setButtonBusy(button, false, "Loading…", "Load transactions");
		});
	}

	function appendCell(row, value) {
		const cell = document.createElement("td");
		cell.className = "wrap";
		cell.textContent = value === undefined || value === null || value === "" ? "—" : String(value);
		row.appendChild(cell);
	}

	function parsePageParam(rawValue, fallback) {
		const parsed = Number.parseInt(rawValue, 10);
		return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
	}

	// ------------------------------------------------------------------
	// 4. deposit
	// ------------------------------------------------------------------

	function initDeposit() {
		const form = byId("deposit-form");
		const submitButton = form.querySelector("button[type=submit]");
		const resultBox = byId("deposit-result");
		const fields = byId("deposit-fields");

		byId("deposit-generate-key").addEventListener("click", () => {
			byId("deposit-idempotency-key").value = crypto.randomUUID();
		});

		form.addEventListener("submit", async (event) => {
			event.preventDefault();
			setButtonBusy(submitButton, true, "Submitting…", "Submit deposit");
			resultBox.hidden = true;

			const accountId = byId("deposit-account-id").value.trim();
			const amount = byId("deposit-amount").value.trim();
			const currency = byId("deposit-currency").value.trim().toUpperCase();
			const idempotencyKey = byId("deposit-idempotency-key").value.trim();

			const result = await apiPostJson(
				`/api/v1/accounts/${encodeURIComponent(accountId)}/deposits`,
				{ amount, currency },
				{ "Idempotency-Key": idempotencyKey });
			renderDevDetails(byId("deposit-dev-details"), result);

			if (result.ok && result.body) {
				renderKeyValueList(fields, [
					["HTTP status", result.status],
					["Transaction ID", result.body.transactionId],
					["Account ID", result.body.accountId],
					["Amount", result.body.amount],
					["Currency", result.body.currency],
					["New balance (from backend)", result.body.newBalance],
					["Created at", result.body.createdAt]
				]);
				addRecentId(STORAGE_KEYS.accounts, result.body.accountId);
			} else {
				renderKeyValueList(fields, [
					["HTTP status", result.networkError ? "(no response)" : result.status],
					["Error", describeApiError(result)]
				]);
			}
			resultBox.hidden = false;
			setButtonBusy(submitButton, false, "Submitting…", "Submit deposit");
		});
	}

	// ------------------------------------------------------------------
	// 5. transfer
	// ------------------------------------------------------------------

	function initTransfer() {
		const form = byId("transfer-form");
		const submitButton = form.querySelector("button[type=submit]");
		const resultBox = byId("transfer-result");
		const fields = byId("transfer-fields");
		const balances = byId("transfer-balances");
		const validationMessage = byId("transfer-validation-message");

		byId("transfer-generate-key").addEventListener("click", () => {
			byId("transfer-idempotency-key").value = crypto.randomUUID();
		});

		form.addEventListener("submit", async (event) => {
			event.preventDefault();
			validationMessage.hidden = true;
			clearChildren(balances);

			const sourceAccountId = byId("transfer-source-id").value.trim();
			const destinationAccountId = byId("transfer-destination-id").value.trim();
			const amount = byId("transfer-amount").value.trim();
			const currency = byId("transfer-currency").value.trim().toUpperCase();
			const idempotencyKey = byId("transfer-idempotency-key").value.trim();

			if (!sourceAccountId || !destinationAccountId || !amount || !currency || !idempotencyKey) {
				validationMessage.textContent = "All fields are required.";
				validationMessage.hidden = false;
				return;
			}
			if (sourceAccountId === destinationAccountId) {
				validationMessage.textContent = "Source and destination accounts must be different.";
				validationMessage.hidden = false;
				return;
			}

			setButtonBusy(submitButton, true, "Submitting…", "Submit transfer");
			resultBox.hidden = true;

			const result = await apiPostJson("/api/v1/transfers",
				{ sourceAccountId, destinationAccountId, amount, currency },
				{ "Idempotency-Key": idempotencyKey });
			renderDevDetails(byId("transfer-dev-details"), result);

			if (result.ok && result.body) {
				renderKeyValueList(fields, [
					["HTTP status", result.status],
					["Transaction ID", result.body.transactionId],
					["Source account ID", result.body.sourceAccountId],
					["Destination account ID", result.body.destinationAccountId],
					["Amount", result.body.amount],
					["Currency", result.body.currency],
					["Created at", result.body.createdAt]
				]);
				addRecentId(STORAGE_KEYS.accounts, result.body.sourceAccountId);
				addRecentId(STORAGE_KEYS.accounts, result.body.destinationAccountId);
				await loadBalancesAfterTransfer(result.body.sourceAccountId, result.body.destinationAccountId, balances);
			} else {
				renderKeyValueList(fields, [
					["HTTP status", result.networkError ? "(no response)" : result.status],
					["Error", describeApiError(result)]
				]);
			}
			resultBox.hidden = false;
			setButtonBusy(submitButton, false, "Submitting…", "Submit transfer");
		});
	}

	/** Reloads both balances from the backend after a successful
	 * transfer — never computed locally. */
	async function loadBalancesAfterTransfer(sourceAccountId, destinationAccountId, container) {
		const [sourceResult, destinationResult] = await Promise.all([
			apiGet(`/api/v1/accounts/${encodeURIComponent(sourceAccountId)}/balance`),
			apiGet(`/api/v1/accounts/${encodeURIComponent(destinationAccountId)}/balance`)
		]);
		const entries = [];
		if (sourceResult.ok && sourceResult.body) {
			entries.push(["Source balance (reloaded)", `${sourceResult.body.balance} ${sourceResult.body.currency}`]);
		} else {
			entries.push(["Source balance (reloaded)", describeApiError(sourceResult)]);
		}
		if (destinationResult.ok && destinationResult.body) {
			entries.push(["Destination balance (reloaded)", `${destinationResult.body.balance} ${destinationResult.body.currency}`]);
		} else {
			entries.push(["Destination balance (reloaded)", describeApiError(destinationResult)]);
		}
		renderKeyValueList(container, entries);
	}

	// ------------------------------------------------------------------
	// 6. settlement CSV import
	// ------------------------------------------------------------------

	function initSettlementImport() {
		const form = byId("settlement-import-form");
		const submitButton = form.querySelector("button[type=submit]");
		const resultBox = byId("settlement-import-result");
		const fields = byId("settlement-import-fields");
		const copyButton = byId("settlement-import-copy");
		let lastImportId = null;

		form.addEventListener("submit", async (event) => {
			event.preventDefault();
			setButtonBusy(submitButton, true, "Uploading…", "Upload and import");
			resultBox.hidden = true;
			copyButton.hidden = true;

			const source = byId("settlement-import-source").value.trim();
			const fileInput = byId("settlement-import-file");
			const file = fileInput.files && fileInput.files[0];

			if (!file) {
				renderKeyValueList(fields, [["Error", "Choose a CSV file first."]]);
				resultBox.hidden = false;
				setButtonBusy(submitButton, false, "Uploading…", "Upload and import");
				return;
			}

			const formData = new FormData();
			formData.append("source", source);
			formData.append("file", file);

			const result = await apiPostForm("/api/v1/settlement-imports", formData);
			renderDevDetails(byId("settlement-import-dev-details"), result);

			if (result.ok && result.body) {
				lastImportId = result.body.importId;
				renderKeyValueList(fields, [
					["HTTP status", result.status],
					["Import ID", result.body.importId],
					["Source", result.body.source],
					["Imported file rows", result.body.totalRows],
					["Newly recorded observations", result.body.insertedRows],
					["Duplicate rows", result.body.duplicateRows],
					["Replayed (exact-file re-upload)", result.body.replayed],
					["Imported at", result.body.importedAt]
				]);
				copyButton.hidden = !lastImportId;
				addRecentId(STORAGE_KEYS.imports, lastImportId);
			} else {
				lastImportId = null;
				renderKeyValueList(fields, [
					["HTTP status", result.networkError ? "(no response)" : result.status],
					["Error", describeApiError(result)]
				]);
			}
			resultBox.hidden = false;
			setButtonBusy(submitButton, false, "Uploading…", "Upload and import");
		});

		copyButton.addEventListener("click", () => copyToClipboard(lastImportId, copyButton, "Copy import ID"));
	}

	// ------------------------------------------------------------------
	// 7. reconciliation
	// ------------------------------------------------------------------

	function initReconciliation() {
		const importIdInput = byId("reconciliation-import-id");

		byId("reconciliation-start-button").addEventListener("click", () => runReconciliationCommand(importIdInput));
		byId("reconciliation-load-button").addEventListener("click", () => loadReconciliationSummary(importIdInput));
		byId("reconciliation-results-button").addEventListener("click", () => loadReconciliationResults(importIdInput));
	}

	function requireImportId(input, devDetailsId) {
		const importId = input.value.trim();
		if (!importId) {
			renderDevDetails(byId(devDetailsId), {
				method: "", path: "", networkError: true, status: 0,
				statusText: "Enter a settlement import ID first.", ok: false, body: null
			});
			return null;
		}
		return importId;
	}

	function renderReconciliationSummary(body) {
		renderKeyValueList(byId("reconciliation-summary-fields"), [
			["Run ID", body.runId],
			["Settlement import ID", body.settlementImportId],
			["Algorithm version", body.algorithmVersion],
			["Imported file rows", body.importedFileRows],
			["Newly recorded observations", body.newlyRecordedObservations],
			["Duplicate rows", body.duplicateRows],
			["Reconciliation result count", body.reconciliationResultCount],
			["Matched count", body.matchedCount],
			["Discrepancy count", body.discrepancyCount],
			["Inconsistent count", body.inconsistentCount],
			["Replayed (already-reconciled import)", body.replayed],
			["Created at", body.createdAt]
		]);
		byId("reconciliation-summary-result").hidden = false;
	}

	async function runReconciliationCommand(input) {
		const importId = requireImportId(input, "reconciliation-summary-dev-details");
		if (!importId) {
			byId("reconciliation-summary-result").hidden = true;
			return;
		}
		const button = byId("reconciliation-start-button");
		setButtonBusy(button, true, "Starting…", "Start reconciliation");

		const result = await apiPostJson(`/api/v1/settlement-imports/${encodeURIComponent(importId)}/reconciliation`, undefined);
		renderDevDetails(byId("reconciliation-summary-dev-details"), result);

		if (result.ok && result.body) {
			renderReconciliationSummary(result.body);
			addRecentId(STORAGE_KEYS.imports, importId);
		} else {
			renderKeyValueList(byId("reconciliation-summary-fields"), [["Error", describeApiError(result)]]);
			byId("reconciliation-summary-result").hidden = false;
		}
		setButtonBusy(button, false, "Starting…", "Start reconciliation");
	}

	async function loadReconciliationSummary(input) {
		const importId = requireImportId(input, "reconciliation-summary-dev-details");
		if (!importId) {
			byId("reconciliation-summary-result").hidden = true;
			return;
		}
		const button = byId("reconciliation-load-button");
		setButtonBusy(button, true, "Loading…", "Load existing reconciliation");

		const result = await apiGet(`/api/v1/settlement-imports/${encodeURIComponent(importId)}/reconciliation`);
		renderDevDetails(byId("reconciliation-summary-dev-details"), result);

		if (result.ok && result.body) {
			renderReconciliationSummary(result.body);
		} else {
			renderKeyValueList(byId("reconciliation-summary-fields"), [["Error", describeApiError(result)]]);
			byId("reconciliation-summary-result").hidden = false;
		}
		setButtonBusy(button, false, "Loading…", "Load existing reconciliation");
	}

	async function loadReconciliationResults(input) {
		const importId = requireImportId(input, "reconciliation-results-dev-details");
		const resultContainer = byId("reconciliation-results-result");
		const body = byId("reconciliation-results-body");
		const emptyState = byId("reconciliation-results-empty");
		const summary = byId("reconciliation-results-summary");
		clearChildren(body);
		emptyState.hidden = true;
		summary.textContent = "";

		if (!importId) {
			resultContainer.hidden = false;
			return;
		}

		const page = parsePageParam(byId("reconciliation-page").value, 0);
		const size = parsePageParam(byId("reconciliation-size").value, 20);
		const params = new URLSearchParams({ page: String(page), size: String(size) });
		const button = byId("reconciliation-results-button");
		setButtonBusy(button, true, "Loading…", "Load results");

		const path = `/api/v1/settlement-imports/${encodeURIComponent(importId)}/reconciliation/results?${params.toString()}`;
		const result = await apiGet(path);
		renderDevDetails(byId("reconciliation-results-dev-details"), result);

		if (result.ok && result.body) {
			const content = Array.isArray(result.body.content) ? result.body.content : [];
			if (content.length === 0) {
				emptyState.hidden = false;
				emptyState.textContent = "No reconciliation results found for this page.";
			} else {
				content.forEach((item) => {
					const row = document.createElement("tr");
					const outcomeCell = document.createElement("td");
					const badge = document.createElement("span");
					badge.className = `outcome-badge outcome-${item.outcome}`;
					badge.textContent = `${outcomeSymbol(item.outcome)} ${item.outcome}`;
					outcomeCell.appendChild(badge);
					row.appendChild(outcomeCell);
					appendCell(row, item.settlementRecordId);
					appendCell(row, item.reportedTransactionId);
					appendCell(row, item.reportedAmount);
					appendCell(row, item.reportedCurrency);
					appendCell(row, item.internalAmount);
					appendCell(row, item.internalCurrency);
					appendCell(row, item.createdAt);
					body.appendChild(row);
				});
			}
			summary.textContent = `Page ${result.body.page} of ${result.body.totalPages} · ${result.body.totalElements} total results`;
		} else {
			emptyState.hidden = false;
			emptyState.textContent = describeApiError(result);
		}
		resultContainer.hidden = false;
		setButtonBusy(button, false, "Loading…", "Load results");
	}

	function outcomeSymbol(outcome) {
		if (outcome === "MATCHED") {
			return "✓";
		}
		if (outcome === "INTERNAL_LEDGER_INCONSISTENT") {
			return "✕";
		}
		return "⚠";
	}

	// ------------------------------------------------------------------
	// clear recent IDs
	// ------------------------------------------------------------------

	function initClearRecentIds() {
		byId("clear-recent-ids").addEventListener("click", () => {
			try {
				window.localStorage.removeItem(STORAGE_KEYS.accounts);
				window.localStorage.removeItem(STORAGE_KEYS.imports);
			} catch (err) {
				/* ignore */
			}
			refreshRecentIdViews();
			byId("clear-recent-ids-status").textContent = "Recent IDs cleared.";
		});
	}

	// ------------------------------------------------------------------
	// init
	// ------------------------------------------------------------------

	document.addEventListener("DOMContentLoaded", () => {
		initAuth();

		byId("status-refresh").addEventListener("click", refreshStatus);
		refreshStatus();

		initCreateAccount();
		initAccountLookup();
		initDeposit();
		initTransfer();
		initSettlementImport();
		initReconciliation();
		initClearRecentIds();

		refreshRecentIdViews();
	});
})();
