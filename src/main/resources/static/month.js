function comparisonValue(text) {
    const value = text.trim().replace(/[  ]/g, " ").replace("−", "-").replace(",", ".");
    return /^[+-]?(?:\d+|\d{1,3}(?: \d{3})+)(?:\.\d+)?$/.test(value)
        ? value.replaceAll(" ", "").replace(/\.0+$/, "") : text;
}

function wholeRubles(text) {
    const value = comparisonValue(text);
    if (value.trim() === "") return 0;
    return /^[+-]?\d+$/.test(value) ? Number(value) : NaN;
}

const historyPercent = new Intl.NumberFormat("ru-RU", {style: "percent", maximumSignificantDigits: 5});

function initMonthForm() {
    const main = document.querySelector("#month, #history");
    const form = main.querySelector("#month-form, #history-form");
    const notice = main.querySelector("#unsaved");
    const yearLink = document.querySelector("[data-year-report]");
    if (!notice) {
        yearLink.onclick = null;
        return;
    }
    const picker = main.querySelector("#period-picker");
    const dialog = main.querySelector("#unsaved-dialog");
    const error = main.querySelector("#request-error");
    const fields = form.querySelectorAll("[data-saved]");
    const shares = form.querySelectorAll("[data-history-share]");
    let destination;

    function markChanges() {
        let dirty = false;
        for (const field of fields) {
            const changed = comparisonValue(field.value) !== comparisonValue(field.dataset.saved);
            field.classList.toggle("modified", changed);
            if (changed) dirty = true;
        }
        notice.hidden = !dirty;
        return dirty;
    }

    function clearError(input) {
        if (input.getAttribute("aria-invalid") !== "true") return;
        document.getElementById(input.getAttribute("aria-describedby"))?.remove();
        input.removeAttribute("aria-invalid");
        input.removeAttribute("aria-describedby");
    }

    for (const editor of form.querySelectorAll(".editor")) {
        const [amount, percent] = editor.querySelectorAll('input[type="text"]');
        const unit = editor.querySelector(".source-unit");
        let timer;
        let version = 0;
        editor.addEventListener("input", event => {
            const input = event.target;
            const other = input === amount ? percent : amount;
            unit.value = input === amount ? "amount" : "percent";
            clearError(other);
            if (percent.disabled) return;
            const request = ++version;
            clearTimeout(timer);
            if (comparisonValue(input.value) === comparisonValue(input.dataset.saved)) {
                other.value = other.dataset.saved;
                unit.value = "amount";
                return;
            }
            other.value = "";
            if (!input.value) return;
            const body = new URLSearchParams({unit: unit.value, value: input.value,
                planningIncome: form.dataset.planningIncome, _csrf: form.elements._csrf.value});
            timer = setTimeout(async () => {
                try {
                    const response = await fetch(form.dataset.linkUrl, {method: "POST", body});
                    if (response.redirected || !response.ok) return;
                    const value = await response.text();
                    if (version === request && main.isConnected) {
                        other.value = value;
                        markChanges();
                    }
                } catch {
                    // The source field stays editable. Save validates that field even if its preview failed.
                }
            }, 100);
        });
    }

    form.addEventListener("input", event => {
        clearError(event.target);
        markChanges();
        updateShares();
    });
    markChanges();
    updateShares();

    function updateShares() {
        if (shares.length === 0) return;
        const income = wholeRubles(form.elements.income.value);
        for (const output of shares) {
            const amount = wholeRubles(form.elements[output.dataset.historyShare].value);
            output.value = income && Number.isFinite(amount) ? historyPercent.format(amount / income) : "—";
        }
    }

    async function save(target) {
        if (main.inert) return;
        const data = new URLSearchParams(new FormData(form));
        main.inert = true;
        error.hidden = true;
        try {
            const response = await fetch(form.action, {method: "POST", body: data});
            const page = new DOMParser().parseFromString(await response.text(), "text/html");
            const updated = page.getElementById(main.id);
            if (response.ok && updated?.querySelector("#save-success")) {
                if (target) {
                    window.location.assign(target);
                    return;
                }
                history.replaceState(null, "", form.action);
            } else if (!updated || (response.status !== 409 && response.status !== 422)) {
                error.textContent = response.status === 403 || page.getElementById("username")
                    ? "Сеанс завершён. Войдите в бюджет в другой вкладке. Черновик остался в форме."
                    : "Сервер не подтвердил сохранение. Черновик остался в форме. Повторите попытку.";
                error.hidden = false;
                return;
            }
            main.replaceWith(updated);
            initMonthForm();
            updated.querySelector("#save-success, #save-conflict, [aria-invalid=true]")?.focus();
        } catch {
            error.textContent = "Нет подтверждения от сервера. Проверьте соединение и нажмите «Сохранить» ещё раз.";
            error.hidden = false;
        } finally {
            main.inert = false;
            if (!error.hidden) error.focus();
        }
    }

    form.addEventListener("submit", event => {
        event.preventDefault();
        save();
    });

    picker.addEventListener("submit", event => {
        if (event.defaultPrevented) return;
        confirmNavigation(event, periodPickerDestination(picker));
    });

    yearLink.onclick = event => {
        if (event.button !== 0 || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
        confirmNavigation(event, yearLink.href);
    };

    function confirmNavigation(event, target) {
        if (main.inert) {
            event.preventDefault();
            return;
        }
        if (!markChanges()) return;
        event.preventDefault();
        destination = target;
        dialog.returnValue = "stay";
        dialog.showModal();
    }

    dialog.addEventListener("click", event => {
        if (event.target === dialog) dialog.close("stay");
    });
    dialog.addEventListener("close", () => {
        picker.reset();
        if (dialog.returnValue === "discard") window.location.assign(destination);
        if (dialog.returnValue === "save") save(destination);
    });
}

initMonthForm();
