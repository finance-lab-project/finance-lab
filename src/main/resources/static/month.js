document.addEventListener("input", (event) => {
    const input = event.target;
    if (input.id === "food" && input.getAttribute("aria-invalid") === "true") {
        document.getElementById("field-error").remove();
        input.removeAttribute("aria-invalid");
        input.setAttribute("aria-describedby", "draft-hint");
    }
});

document.addEventListener("submit", async (event) => {
    const form = event.target;
    if (form.id !== "month-form" || !form.elements.food) return;
    event.preventDefault();

    const save = form.querySelector('button[type="submit"]');
    if (save.disabled) return;
    const input = form.elements.food;
    const error = document.getElementById("request-error");
    const data = new URLSearchParams(new FormData(form));
    save.disabled = true;
    input.readOnly = true;
    error.hidden = true;

    try {
        const response = await fetch(form.action, {method: "POST", body: data});
        const page = new DOMParser().parseFromString(await response.text(), "text/html");
        const month = page.getElementById("month");

        if (response.ok && month?.querySelector("#save-success")) {
            document.getElementById("month").replaceWith(month);
            history.replaceState(null, "", form.action);
            month.querySelector("#save-success").focus();
        } else if (month && (response.status === 409 || response.status === 422)) {
            document.getElementById("month").replaceWith(month);
            (month.querySelector("#save-conflict") || month.querySelector("#food")).focus();
        } else {
            const message = response.status === 403 || page.getElementById("username")
                ? "Сеанс завершён. Войдите в бюджет в другой вкладке и откройте месяц заново. Черновик остался в этом поле."
                : "Сервер не подтвердил сохранение. Черновик остался в поле. Повторите попытку.";
            error.textContent = message;
            error.hidden = false;
            error.focus();
        }
    } catch {
        error.textContent = "Нет подтверждения от сервера. Проверьте соединение и нажмите «Сохранить» ещё раз.";
        error.hidden = false;
        error.focus();
    } finally {
        input.readOnly = false;
        save.disabled = false;
    }
});
