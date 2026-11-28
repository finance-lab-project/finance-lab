const assert = require("node:assert/strict");
const {readFileSync} = require("node:fs");
const {resolve} = require("node:path");
const {test} = require("node:test");
const {runInNewContext} = require("node:vm");

const script = readFileSync(resolve(__dirname, "../../main/resources/static/period-picker.js"), "utf8");

function pickerHarness({latestPeriod, guard, yearOnly} = {}) {
    const listeners = new Map();
    const destinations = [];
    let validationMessage = "";
    const year = {
        name: "year", value: "2027", defaultValue: "2027",
        setCustomValidity(message) { validationMessage = message; },
        reportValidity() {}
    };
    const month = yearOnly ? undefined : {
        name: "month", value: "2",
        get selectedOptions() { return [{defaultSelected: this.value === "2"}]; }
    };
    const picker = {
        id: "period-picker", action: "https://budget.example/periods",
        dataset: {latestPeriod}, elements: {year, month},
        checkValidity() { return !validationMessage && /^[0-9]{4}$/.test(year.value); },
        requestSubmit() { dispatch("submit", this); },
        reset() {
            year.value = year.defaultValue;
            if (month) month.value = "2";
        }
    };

    function dispatch(type, target) {
        const event = {type, target, defaultPrevented: false,
            preventDefault() { this.defaultPrevented = true; }
        };
        const handlers = listeners.get(type);
        handlers.capture?.(event);
        if (type === "submit" && !event.defaultPrevented) guard?.(event);
        handlers.bubble?.(event);
    }

    year.form = picker;
    if (month) month.form = picker;
    runInNewContext(script, {
        URL, URLSearchParams,
        FormData: class {
            constructor() {
                return month ? [["month", month.value], ["year", year.value]] : [["year", year.value]];
            }
        },
        document: {
            addEventListener(type, handler, capture) {
                if (!listeners.has(type)) listeners.set(type, {});
                listeners.get(type)[capture ? "capture" : "bubble"] = handler;
            }
        },
        window: {location: {assign: target => destinations.push(target.search)}}
    });

    return {
        destinations, year, month,
        typeYear(value) { year.value = value; dispatch("input", year); },
        chooseMonth(value) { month.value = value; dispatch("change", month); }
    };
}

test("year input waits for four digits and navigates without a separate submit", () => {
    const picker = pickerHarness({yearOnly: true});
    for (const partial of ["2", "20", "202"]) picker.typeYear(partial);
    assert.deepEqual(picker.destinations, []);
    picker.typeYear("2028");
    assert.deepEqual(picker.destinations, ["?year=2028"]);
    assert.equal(picker.year.value, "2027");
});

test("committing a different month navigates and resets the outgoing picker", () => {
    const picker = pickerHarness();
    picker.chooseMonth("2");
    assert.deepEqual(picker.destinations, []);
    picker.chooseMonth("1");
    assert.deepEqual(picker.destinations, ["?month=1&year=2027"]);
    assert.equal(picker.month.value, "2");
});

test("a future history period is blocked before the editor guard", () => {
    let guardCalls = 0;
    const picker = pickerHarness({latestPeriod: "2027-02", guard: () => guardCalls++});
    picker.chooseMonth("3");
    assert.equal(guardCalls, 0);
    assert.deepEqual(picker.destinations, []);
    picker.typeYear("2026");
    assert.equal(guardCalls, 1);
    assert.deepEqual(picker.destinations, ["?month=3&year=2026"]);
});

test("the editor guard can cancel automatic navigation", () => {
    const picker = pickerHarness({guard: event => event.preventDefault()});
    picker.chooseMonth("1");
    assert.deepEqual(picker.destinations, []);
    assert.equal(picker.month.value, "1");
});
