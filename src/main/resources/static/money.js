const moneyFields = "input[data-money]";
const moneySuffix = "\u00a0₽";
const moneySpaces = /[ \u00a0\u202f]/g;

function moneyNumber(value) {
    return value.trim().replace(/[ \u00a0\u202f]*₽$/, "");
}

function formatMoney(value) {
    const number = moneyNumber(value);
    if (!number && !value.includes("₽")) return "";
    if (!/^[+−-]?(?:\d+|\d{1,3}(?:[ \u00a0\u202f]\d{3})+)(?:[,.]\d*)?$/.test(number)) return value;
    const [whole, fraction] = number.replace(moneySpaces, "").replace("-", "−").split(/[,.]/);
    const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, "\u00a0");
    return grouped + (fraction === undefined ? "" : "," + fraction) + moneySuffix;
}

function formatMoneyFields(root) {
    for (const input of root.querySelectorAll(moneyFields)) {
        const formatted = formatMoney(input.value);
        if (formatted !== input.value) input.value = formatted;
    }
}

function moneyCaret(value, offset) {
    let position = 0;
    while (offset > 0 && position < value.length) {
        if (value[position] !== "\u00a0") offset--;
        position++;
    }
    return position;
}

const moneyEdits = new WeakMap();

document.addEventListener("beforeinput", event => {
    const input = event.target;
    if (!input.matches(moneyFields) || event.isComposing) return;
    moneyEdits.set(input, {value: input.value, start: input.selectionStart, end: input.selectionEnd});
}, true);

document.addEventListener("input", event => {
    const input = event.target;
    if (!input.matches(moneyFields) || event.isComposing) return;
    const previous = moneyEdits.get(input);
    moneyEdits.delete(input);
    let value = input.value;
    let caret = input.selectionStart;
    if (previous?.value.endsWith(moneySuffix) && event.inputType !== "historyUndo" && event.inputType !== "historyRedo") {
        const end = previous.value.length - moneySuffix.length;
        const collapsed = previous.start === previous.end;
        const backward = collapsed && event.inputType === "deleteContentBackward";
        const forward = collapsed && event.inputType === "deleteContentForward";
        if (backward && (previous.start > end || previous.value[previous.start - 1] === "\u00a0")) {
            const digit = previous.start > end ? end - 1 : previous.start - 2;
            value = previous.value.slice(0, digit) + previous.value.slice(digit + 1, end);
            caret = digit;
        } else if (forward && previous.start < end && previous.value[previous.start] === "\u00a0") {
            value = previous.value.slice(0, previous.start) + previous.value.slice(previous.start + 2, end);
            caret = previous.start;
        } else {
            // Strip the surviving part of the old suffix, including when typing after it.
            const prefix = previous.value.slice(end, Math.max(end, previous.start));
            const suffix = previous.value.slice(Math.max(end, previous.end));
            const left = backward ? prefix.slice(0, -1) : prefix;
            const right = forward && previous.end >= end ? suffix.slice(1) : suffix;
            if (right && value.endsWith(right)) value = value.slice(0, -right.length);
            if (left && value.slice(end, end + left.length) === left) {
                value = value.slice(0, end) + value.slice(end + left.length);
                caret -= left.length;
            }
        }
        // Native edits can split or leave behind a grouping separator.
        if ((previous.start !== 0 || previous.end < end) && /^[+−-]?[\d \u00a0\u202f]*([,.]\d*)?$/.test(value)) {
            caret = value.slice(0, caret).replace(moneySpaces, "").length;
            value = value.replace(moneySpaces, "");
        }
    }
    const formatted = formatMoney(value);
    if (formatted === input.value) return;
    input.value = formatted;
    const position = formatted.endsWith(moneySuffix) && formatted !== value
        ? moneyCaret(moneyNumber(formatted), moneyNumber(value.slice(0, caret)).replace(moneySpaces, "").length)
        : caret;
    input.setSelectionRange(position, position);
}, true);

formatMoneyFields(document);
