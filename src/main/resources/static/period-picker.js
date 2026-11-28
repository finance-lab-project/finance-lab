function periodPickerDestination(picker) {
    const target = new URL(picker.action);
    target.search = new URLSearchParams(new FormData(picker)).toString();
    return target;
}

function submitSelectedPeriod(event) {
    const {form: picker, name} = event.target;
    if (picker?.id !== "period-picker" || event.isComposing) return;
    if (event.type === "input" && name !== "year") return;
    if (event.type === "change" && name !== "month") return;

    picker.elements.year.setCustomValidity("");
    if (picker.checkValidity()) picker.requestSubmit();
}

document.addEventListener("input", submitSelectedPeriod);
document.addEventListener("change", submitSelectedPeriod);

// Validate before the editor can open its unsaved-changes dialog.
document.addEventListener("submit", event => {
    const picker = event.target;
    if (picker.id !== "period-picker") return;

    const {year, month} = picker.elements;
    const selectedPeriod = month && year.value + "-" + month.value.padStart(2, "0");
    if (year.value === "0000" || (picker.dataset.latestPeriod && selectedPeriod > picker.dataset.latestPeriod)) {
        event.preventDefault();
        year.setCustomValidity(year.value === "0000" ? "Введите год от 0001 до 9999." : "Выберите завершённый месяц.");
        year.reportValidity();
    } else if (year.value === year.defaultValue && (!month || month.selectedOptions[0].defaultSelected)) {
        event.preventDefault();
    }
}, true);

document.addEventListener("submit", event => {
    const picker = event.target;
    if (picker.id !== "period-picker" || event.defaultPrevented) return;

    event.preventDefault();
    const target = periodPickerDestination(picker);
    picker.reset();
    window.location.assign(target);
});
