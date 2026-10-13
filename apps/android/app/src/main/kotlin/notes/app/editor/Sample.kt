package notes.app.editor

/** A note exercising every construct, for manual QA in debug builds. */
const val SAMPLE_TITLE = "Weekend plan"

val SAMPLE_MARKDOWN = """
    |# Saturday
    |
    |Pick up the **bike** from the shop, then *maybe* the ~~market~~ park.
    |
    |- Groceries
    |  - Bread
    |  - Coffee beans
    |    - [the good ones](https://example.com/coffee)
    |- Laundry
    |
    |## Checklist
    |
    |- [x] Book the table
    |- [ ] Call Sam
    |  - [ ] Ask about Sunday
    |
    |1. Leave at 9
    |2. Lunch by the river
    |
    |> Rest is part of the plan.
    |
    |```sh
    |rsync -av ~/photos /backup
    |```
    |
    |Run `make deploy` when back.
""".trimMargin() + "\n"
