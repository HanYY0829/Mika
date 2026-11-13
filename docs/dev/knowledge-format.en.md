# Format of built-in knowledge and skills

[简体中文](knowledge-format.md) | English

Knowledge and skills ship with the app, under `knowledge/apps/`, `knowledge/sites/` and `skills/` in `core/src/main/resources/`. A user's own personal skills are drafted by Mika on the phone as it learns. App knowledge is also extended on the phone as Mika learns: the addition is a facts-only list (each line starts with `- `), likewise only facts and no methods or rules, appended after the built-in APP.md (see "Knowledge and skills" in `docs/dev/architecture.md`).

Three kinds of file, each with one job:

| File | Covers | Does not cover |
|---|---|---|
| `knowledge/apps/<id>/APP.md` | What the app is, how its UI is organized, what features it has, what can be seen in it | Rules of behavior, ways of doing things |
| `knowledge/sites/<main-domain>/SITE.md` | What the website is, how its pages and URLs are organized, what features it has, public APIs | Rules of behavior, ways of doing things |
| `skills/<id>/SKILL.md` | How to do a kind of task (a reusable method that can span apps and websites) | Methods specific to one industry or product category |

## Common requirements

- **Facts only.** Rules such as authorization, confirmation, stop conditions, "don't make things up" or "hand over to the user" belong to the general guidelines and the approval layer, not to knowledge.
- **Write it in Mika's own words.** Avoid format jargon such as 「本批」 ("this batch"), 「证据」 ("evidence"), 「总则」 ("general rules"), 「通道」 ("channel") and "scope/stops/features".
- **Descriptions don't cite skills.** A skill may name an app or website (`app:<id>`, `site:<domain>`); an app or website description never says "follow skill XX".
- **Describe what happens as what happens.** Write "the 'Delivery' tile in the home grid and the 'Delivery' tab at the bottom are two different entry points; the first opens the delivery channel", not "don't tap the bottom tab".
- When describing UI, say "screen", "page", "entry point", "button"; don't say "look at the screen".
- Keep the body under 2500 characters. Put large reference material (station code tables, URL formats, search syntax) in `references/`, and say in the body when to read which attachment.
- Keep facts such as package names, domains, URLs, API endpoints and field names exactly as they are. If you're not sure of a fact, leave it out.

Existing knowledge is written in Chinese; new files can be written in English. The examples below keep the Chinese values from real files.

## APP.md

```markdown
---
name: bilibili                     # directory name: lowercase letters, digits, hyphens
display: 哔哩哔哩
description: 视频、直播与动态(at most 60 characters: what this app can do)
packages: ["tv.danmaku.bili", "com.bilibili.app.in"]
package_aliases: ["国际版 | com.bilibili.app.in"]     # optional: which package a given name refers to
aliases: ["B站", "哔哩哔哩"]
sites: ["bilibili.com"]            # optional: matching website knowledge
open: 哔哩哔哩                       # name used to open the app
pace: careful                      # optional: this app limits how fast it can be used (acting too fast or viewing too much gets blocked, asks for verification or logs you out)
links: ["首页 | bilibili://... | tv.danmaku.bili"]       # optional
---
## 是什么 (What it is)
One or two sentences: what service the app provides; if it has several separate packages, what each one is.

## 界面 (UI)
Main pages, bottom tabs, the search entry point, where common features are.

## 功能 (Features)
- **Feature name**: how to get there; whether sign-in is needed; what information it shows (prices, status, fields).

## 常用流程 (Common flows)
Optional: something that is asked for often and takes only a few steps (post a Moments, send money, search for a kind of content). If the steps are few, write them in the body; if there are many and they must be matched against specific menu names, put the steps in a `references/` attachment and say in the body when to read which one. Leave this section out if there is nothing to say.

## 已知现象 (Known behavior)
Entry points that are easy to confuse, common pop-ups, quirks in loading or refreshing. Leave this section out if there is nothing to say.
```

## SITE.md

```markdown
---
name: bilibili.com                  # main domain
display: 哔哩哔哩
description: 视频、搜索与用户主页
domains: ["bilibili.com", "b23.tv"]
aliases: ["B站", "bilibili"]
apps: ["bilibili"]                   # optional: matching app knowledge
open: bilibili.com                  # URL used to open it
mode: desktop                      # desktop / mobile: which version of the pages to use
pace: careful                      # optional: this website limits how fast it can be browsed (viewing too fast or too much gets blocked)
links: ["..."]                     # optional
---
## 是什么 (What it is)

## 页面与网址 (Pages and URLs)
Main pages, how URLs are written (formats with parameters), the search entry point.

## 功能 (Features)
- **Feature name**: on which page; whether sign-in is needed; what information it shows.

## 接口 (APIs)
Optional: addresses, parameters and response formats of public APIs.

## 已知现象 (Known behavior)
Optional.
```

`pace` is a fact about the website, not a rule for the model. The only allowed value is `careful`, meaning the website limits how fast one visitor can browse (turning pages or opening details too fast or too often gets blocked or asks for verification); leaving it out means there is no such limit. On a website marked `careful`, Mika waits longer before moving to another page on the same site (about 10–22 seconds, longer the more it has viewed, at most 30 seconds), and the number of pages it views on that site in one task is capped lower (20 pages on an ordinary site, 12 on these; the count restarts after the user agrees to keep going; it only limits browsing, and stops applying once an outward action such as sending or submitting has succeeded on that site). Checked at build time: apps and websites may set it (skills may not), and a wrong value fails the build.

`pace` on an app is the same kind of fact: an app marked `careful` limits how fast one account can act (tapping too fast or scrolling too much gets blocked, asks for verification, or even logs you out). Mika recognizes the app by package name (any of those in `packages`) and waits longer before each step (about 2.5–5 seconds on a new screen, plus reading time based on how much text the screen has, at most 15 seconds; about 1–2 seconds when continuing on the same screen), now and then scrolls down a little while waiting, and types more slowly. In one task it moves to a new screen in that app at most 24 times (opening and going back each count once; scrolling to read the same page doesn't; the count restarts after the user agrees to keep going; it stops applying once an outward action such as sending or submitting has succeeded). None of this applies when the operation pace in Settings is set to "Fast".

A website directory can have `scripts/`: site commands (.js files with a `/* mika-command` header) or sandbox scripts (listed in `scripts/manifest.json`).

## SKILL.md

Same format as the personal skills Mika drafts as it learns (see `prompts/roles/skill_draft.md`):

```markdown
---
name: pdf-text
display: 取 PDF 文字
description: one sentence: when to use this skill
aliases: ["读 PDF", "PDF 转文字"]
apps: []                           # optional: app knowledge names it uses
sites: []                          # optional: website knowledge names it uses
---
## 入口 (Entry point)
## 步骤 (Steps)
## 容易出错的地方 (Common mistakes)
## 怎样算办成(核实证据) (What counts as done, and how to check)
## 什么时候停下交给用户 (When to stop and hand over to the user)
```

A skill describes how to do "this kind of task", regardless of industry or product category. It can come with `references/` attachments and `scripts/`.

## Header fields to keep or drop

Drop: `risk`, `status`, `updated`, `verified_on` (metadata not used at runtime).
`terms` is for skills only; apps and websites use `aliases`.

## How the agent uses these files

Knowledge (apps and websites) and skills each have a pair of "search / read" tools:

| Tool | Searches | Parameters |
|---|---|---|
| `knowledge_search` | App and website knowledge, up to 10 entries of `{name, display, kind, description}` | `query`; optional `kind` (`app` / `site`) |
| `knowledge_read` | Reads one piece of app or website knowledge, or one of its attachments | `name` (can be written as `app:<id>` or `site:<domain>`); optional `file_path` (an attachment in `references/`) |
| `skill_search` | Skills, built-in and the user's personal ones together, up to 10 entries | `query` |
| `skill_read` | Reads a full skill, or one of its attachments | `name` (can be written as `skill:<id>`, or `personal:<id>` for a personal skill); optional `file_path` |

- When Mika enters an app or website, the body of its knowledge has already been given to the model; `knowledge_read` is mainly for reading attachments, or for apps and websites it hasn't entered yet.
- The results of `knowledge_read` and `skill_read` list runnable scripts and their `scriptSource`; run them in the sandbox with `run_bundled_script` (`source`, `script`, `args`), see `docs/dev/skill-scripts.md`.
- A website's site commands run by name with `web_run_site_command`, read-only like `web_evaluate_js`.
- Tool names that appear in knowledge, skills and prompts must be currently registered tools; a test checks this.
