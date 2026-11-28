const form = document.querySelector("#plan-form");
const rows = form.querySelector("#plan-rows");
const save = form.querySelector('[type="submit"]');
const message = form.querySelector("#plan-error");
const queue = [];
const rubles = new Intl.NumberFormat("ru-RU");
let view = JSON.parse(form.dataset.initial);
let draft = view.draft;
let validation = view.error;
let running;
let timer;
let blocked = false;

function showError(text) {
    message.textContent = text || "";
    message.hidden = !text;
}

function rowFields(element, group) {
    for (const label of element.querySelectorAll("[data-group]")) {
        label.hidden = label.dataset.group !== group;
    }
}

function addCard(row) {
    const card = document.querySelector("#category-template").content.firstElementChild.cloneNode(true);
    card.querySelector('[data-field="name"]').value = row.name;
    card.querySelector('[data-field="group"]').value = row.group;
    card.querySelector(".remove-row").hidden = Boolean(row.code);
    rowFields(card, row.group);
    rows.append(card);
    return card;
}

function update(input, value, editing) {
    if (input !== editing || input !== document.activeElement) {
        input.value = input.matches(moneyFields) ? formatMoney(value) : value;
    }
}

function display(editing) {
    update(form.querySelector("#income"), draft.income, editing);
    update(form.querySelector("#target-percent"), view.targetPercent, editing);
    update(form.querySelector("#target-amount"), view.targetAmount, editing);
    const zeroIncome = draft.income === "0";
    form.querySelector("#target-amount").disabled = zeroIncome;
    for (const [i, row] of draft.rows.entries()) {
        const card = rows.children[i];
        const fields = view.fields[i];
        card.querySelector("legend").textContent = row.name || "Новая категория";
        rowFields(card, row.group);
        const zeroPool = row.group === "discretionary" && view.pool === "0";
        for (const input of card.querySelectorAll("input[data-field]")) {
            const key = input.dataset.field;
            if (key === "name") continue;
            update(input, fields[key], editing);
            input.disabled = (key === "incomePercent" && (zeroIncome || zeroPool)) || (key === "amount" && zeroPool);
        }
    }
    for (const key of ["needs", "investments", "pool"]) {
        form.querySelector("#preview-" + key).textContent = rubles.format(BigInt(view[key])) + " ₽";
    }
    const shortfall = form.querySelector("#preview-shortfall");
    shortfall.hidden = view.shortfall === "0";
    shortfall.textContent = "На необходимые расходы не хватает " + rubles.format(BigInt(view.shortfall)) + " ₽.";
}

async function post(url, body) {
    const response = await fetch(url, {method: "POST", headers: {
        "Content-Type": "application/json", [form.dataset.csrfHeader]: form.elements._csrf.value
    }, body: JSON.stringify(body)});
    if (response.redirected || response.status === 403) {
        throw new Error("Сеанс завершён. Войдите в бюджет в другой вкладке. Черновик остался на экране.");
    }
    if (!response.ok && response.status !== 422 && response.status !== 409) {
        throw new Error("Сервер не подтвердил запрос. Проверьте соединение и повторите попытку.");
    }
    return response;
}

// Apply edits in order: a ruble entry fixes a share of the pool at that instant.
// A subsequent income change reuses that exact share, even on a slow connection.
async function processQueue() {
    while (queue.length) {
        const job = queue.shift();
        const next = {...draft, rows: draft.rows.map(row => ({...row}))};
        job.apply(next);
        try {
            const response = await post(form.dataset.preview, next);
            const result = await response.json();
            if (response.ok) {
                view = result;
                draft = result.draft;
                validation = result.error;
                if (!queue.length) display(job.input);
            } else {
                draft = next;
                validation = result.error;
            }
        } catch (error) {
            queue.unshift(job);
            showError(error instanceof TypeError ? "Нет связи с сервером. Черновик сохранён на экране. Повторите попытку." : error.message);
            save.disabled = false;
            return false;
        }
    }
    showError(validation);
    save.disabled = Boolean(validation);
    return !validation;
}

function flush() {
    clearTimeout(timer);
    if (!running) running = processQueue().finally(() => { running = null; });
    return running;
}

function enqueue(input, apply) {
    const job = {input, apply};
    if (input && queue.at(-1)?.input === input) queue.pop();
    queue.push(job);
    save.disabled = false;
    clearTimeout(timer);
    timer = setTimeout(flush, 80);
}

draft.rows.forEach(addCard);
display(null);
showError(validation);
save.disabled = Boolean(validation);

form.querySelector("#add-category").addEventListener("click", () => {
    const row = {code: "", name: "", group: "essential", unit: "amount", value: "0"};
    const card = addCard(row);
    enqueue(null, next => next.rows.push(row));
    card.querySelector('[data-field="name"]').focus();
});

rows.addEventListener("click", event => {
    if (!event.target.matches(".remove-row")) return;
    const card = event.target.closest(".plan-row");
    const i = Array.from(rows.children).indexOf(card);
    card.remove();
    enqueue(null, next => next.rows.splice(i, 1));
});

form.addEventListener("input", event => {
    const input = event.target;
    const value = input.value;
    const key = input.dataset.field;
    if (input.id === "income") {
        enqueue(input, next => { next.income = value; });
    } else if (input.id === "target-percent" || input.id === "target-amount") {
        enqueue(input, next => {
            next.targetUnit = input.id === "target-percent" ? "incomePercent" : "amount";
            next.targetValue = value;
        });
    } else if (key) {
        const card = input.closest(".plan-row");
        const i = Array.from(rows.children).indexOf(card);
        if (key === "group") rowFields(card, value);
        enqueue(input, next => {
            const row = next.rows[i];
            if (key === "name") {
                row.name = value;
            } else if (key === "group") {
                row.group = value;
                row.unit = value === "essential" ? "amount" : "fraction";
                row.value = value === "essential" ? (view.fields[i]?.amount || "0") : "0/1";
            } else if (key === "annual" && value === "") {
                row.unit = "amount";
                row.value = view.fields[i]?.amount || "0";
            } else {
                row.unit = key;
                row.value = value;
            }
        });
    }
});

form.addEventListener("submit", async event => {
    event.preventDefault();
    if (blocked) return;
    blocked = true;
    form.inert = true;
    try {
        if (!await flush()) return;
        const response = await post(form.action, {period: form.dataset.period, revision: form.dataset.revision, draft});
        if (response.status === 204) {
            window.location.assign(form.dataset.month);
            return;
        }
        showError((await response.json()).error);
    } catch (error) {
        showError(error instanceof TypeError ? "Нет подтверждения от сервера. Проверьте соединение и нажмите «Сохранить» ещё раз." : error.message);
    } finally {
        form.inert = false;
        blocked = false;
    }
});
