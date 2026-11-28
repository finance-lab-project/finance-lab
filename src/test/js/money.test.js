const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const test = require("node:test");

function editor(initial = "") {
    const handlers = {};
    const input = {
        value: initial,
        matches: () => true,
        setSelectionRange(start, end) {
            this.selectionStart = start;
            this.selectionEnd = end;
        }
    };
    const document = {
        querySelectorAll: () => [input],
        addEventListener: (type, handler) => { handlers[type] = handler; }
    };
    vm.runInNewContext(fs.readFileSync("src/main/resources/static/money.js", "utf8"), {document});
    const edit = (start, end, text, inputType) => {
        input.setSelectionRange(start, end);
        const event = {target: input, inputType};
        handlers.beforeinput(event);
        if (start === end && inputType === "deleteContentBackward") start--;
        if (start === end && inputType === "deleteContentForward") end++;
        input.value = input.value.slice(0, start) + text + input.value.slice(end);
        input.setSelectionRange(start + text.length, start + text.length);
        handlers.input(event);
    };
    return {input, edit};
}

test("typing and pasting group rubles and leave the caret before the currency", () => {
    const {input, edit} = editor();
    for (const digit of "30000") edit(input.selectionStart ?? 0, input.selectionStart ?? 0, digit, "insertText");
    assert.equal(input.value, "30\u00a0000\u00a0₽");
    assert.equal(input.selectionStart, 6);
    edit(0, input.value.length, "30\u202f000 ₽", "insertFromPaste");
    assert.equal(input.value, "30\u00a0000\u00a0₽");
});

test("middle insertion and deletion keep the caret next to the edited digits", () => {
    const {input, edit} = editor("30 000");
    edit(1, 1, "1", "insertText");
    assert.equal(input.value, "310\u00a0000\u00a0₽");
    assert.equal(input.selectionStart, 2);
    edit(4, 4, "", "deleteContentBackward");
    assert.equal(input.value, "31\u00a0000\u00a0₽");
    assert.equal(input.selectionStart, 2);
});

test("backspace can clear explicit zero, including from the end of the suffix", () => {
    const {input, edit} = editor();
    assert.equal(input.value, "");
    edit(0, 0, "0", "insertText");
    assert.equal(input.value, "0\u00a0₽");
    edit(input.value.length, input.value.length, "", "deleteContentBackward");
    assert.equal(input.value, "");
    assert.equal(input.selectionStart, 0);
});

test("money formatting retains fractional digits and invalid input for validation", () => {
    const {input, edit} = editor();
    edit(0, 0, "-30000.01", "insertFromPaste");
    assert.equal(input.value, "−30\u00a0000,01\u00a0₽");
    edit(0, input.value.length, "3 00", "insertFromPaste");
    assert.equal(input.value, "3 00");
});
