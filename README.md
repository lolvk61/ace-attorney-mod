<div align="center">

<img src="src/main/resources/assets/aceattorney/icon.png" width="96" alt="Ace Attorney Mod">

# Ace Attorney Mod

**Courtroom drama for Minecraft.** Hold real trials with your friends: roles, evidence,
cross-examination, a court clerk keeping the record — and of course full-screen **OBJECTION!** shouts.

<img src="src/main/resources/assets/aceattorney/textures/gui/shout_objection.png" width="360" alt="OBJECTION!">

![build](https://github.com/lolvk61/ace-attorney-mod/actions/workflows/build.yml/badge.svg)

Fabric • Minecraft 1.21.11 • Java 21 • [Download](../../releases/latest)

[English](#english) | [Русский](#русский)

</div>

---

## English

### Features
- **Shouts** — press `O` / `H` / `J` and everyone within 64 blocks sees a full-screen
  *OBJECTION! / HOLD IT! / TAKE THAT!* speech bubble with sound and screen shake.
- **Courtroom furniture** — judge's bench, witness stand, clerk's bench and desks for the
  defense, prosecution and defendant. Right-click a seat to take that role; right-click the
  judge's bench to open the session. Blocks are proper 3D models that face you when placed.
- **Court Record GUI** (`G`) — evidence and testimony lists, present / press / object buttons,
  a form to submit the item in your hand as evidence, AA-style speech, judge's verdict panel.
- **Cross-examination** — the witness and the defendant testify (statements are grouped by
  speaker), the defense presses statements and objects with contradicting evidence, testimony
  can be amended by its author or the judge.
- **Court clerk & protocol** — every action and line of speech is recorded with timestamps.
  The clerk can view and export the live protocol; anyone can export protocols of concluded
  cases from the persistent case log.
- **Several trials at once** — sessions can run side by side as long as they are at least 50 blocks
  apart. What a session says (chat lines, titles, dialogues, shouts) reaches only its participants and
  the players within 50 blocks of it; everyone else is left alone.
- **Dialogue boxes** — `/aa say <text>` shows a typewriter-style AA dialogue box to players nearby.
- Full command fallback under `/court` for everything the GUI does.

### Installation
1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and the
   [mod jar](../../releases/latest) into your `mods` folder.
3. Pick how the server takes part — there are two ways:

| Server | What to install | Courtroom blocks & items |
|--------|-----------------|--------------------------|
| **Fabric** | the mod on the server and on every client | yes |
| **Paper / Spigot / Purpur** | the `AceAttorneyRelay` plugin on the server, the mod on the clients that want to play | no — roles are picked in the GUI |

**Client-only mode (Paper).** Put `AceAttorneyRelay-<version>.jar` (from the
[releases](../../releases/latest)) into the server's `plugins` folder. Players with the mod can then
hold trials together: the plugin forwards court events only to clients that have the mod, so nothing
is written to chat and players without the mod notice nothing. The mod does not add any blocks or
items in this mode (they would not exist on the server), so seats are taken with the **Role** button in
the Court Record GUI (`G`). The case journal is stored per server in `config/aceattorney/relay_cases`
on each client. A player who joins mid-session receives the current state from the judge (or another
participant); the protocol then starts from the moment of joining.

### Building
```
./gradlew build                # the mod  -> build/libs/
./gradlew -p plugin build      # the Paper plugin -> plugin/build/libs/
```

---

## Русский

Мод для судебных ролевых процессов в духе Ace Attorney: заседания, роли, улики,
перекрёстный допрос, протокол секретаря и выкрики «ПРОТЕСТУЮ!» на весь экран.

### Выкрики
Работают в мультиплеере — плашку видят все в радиусе 64 блоков:

| Клавиша | Выкрик |
|---------|--------|
| `O` | OBJECTION! (Протестую!) |
| `H` | HOLD IT! (Минуточку!) |
| `J` | TAKE THAT! (Вот!) |

Клавиши переназначаются в настройках управления (категория «Ace Attorney»).

### Зал суда — блоки
Мебель во вкладке «Ace Attorney» в креативе. ПКМ по блоку — занять место:

| Блок | Роль |
|------|------|
| Скамья судьи | начать заседание / судья; повторный клик судьёй — «Порядок в зале суда!» |
| Трибуна свидетеля | Свидетель |
| Стол защиты (синяя полоса) | Адвокат защиты |
| Стол обвинения (красная) | Прокурор |
| Стол обвиняемого (серая) | Подсудимый |
| Стол секретаря (бирюзовая) | Секретарь заседания |

Shift+ПКМ — обычное взаимодействие (можно строить). Блоки поворачиваются лицом к игроку при установке.

### Судебное дело — GUI (клавиша `G`)
- Слева улики, справа показания, сгруппированные по свидетелям (клик по имени — его показания)
- **Предъявить** улику, **Надавить** на показание (только защита), **Протест!** с уликой или без
- **Приобщить…** — предмет в руке становится уликой с названием и описанием
- Показания дают только свидетель и обвиняемый; автор (или судья) может **✎ Изменить** показание
- Строка **Сказать** — реплика в диалоговом окне в стиле AA (то же, что `/aa say`)
- Судье — панель **ВИНОВЕН / НЕВИНОВЕН / Завершить заседание**
- Перед началом заседания можно ввести название дела

### Секретарь и протокол
С начала заседания записывается всё: кто во сколько занял место, что сказал, какую улику
приобщил или предъявил, показания с правками («было → стало»), протесты, вердикт.

- Кнопка **Протокол** в GUI и `/court protocol` — только для секретаря
- **💾 Экспорт** — секретарь выгружает протокол прямо во время заседания
- После окончания дела протокол сохраняется в журнал; **любой игрок** может выбрать дело
  в **Журнале** и экспортировать его протокол
- Файлы: `<папка игры>/aceattorney_protocols/delo_N.txt`, в чате — кликабельная ссылка

### Несколько заседаний одновременно
На одном сервере могут идти несколько заседаний, если между ними **не меньше 50 блоков**:

- Заседание «привязано» к месту, где оно открыто (скамья судьи или позиция игрока, открывшего дело).
  Если в радиусе 50 блоков уже идёт другое заседание, новое не откроется — игрок увидит, какое дело
  рядом и в скольких блоках оно находится.
- Блоки зала (столы, трибуна) работают на заседание, идущее вокруг них; роль берётся там, где ты стоишь.
- Сообщения, титры, диалоги, выкрики и состояние GUI заседания получают только его участники (где бы
  они ни были) и игроки в радиусе 50 блоков — они смотрят дело как зрители. Чужие заседания их не
  касаются.
- Игрок участвует только в одном заседании: новая роль или новое дело в другом месте переносит его
  туда. Выйти можно командой `/court leave` (или кнопкой в окне «Роль» в режиме плагина). Когда уходит
  последний участник, заседание закрывается.
- Заседание, в котором никого из участников нет в сети, не блокирует место: оно закрывается само, как
  только рядом кто-то открывает новое дело.
- Номера дел сквозные и не повторяются; `/court list` показывает идущие заседания и расстояние до них.

### Журнал дел
Каждое заседание получает сквозной номер. Вердикты записываются автоматически и переживают
перезапуск сервера (файл `aceattorney_case_log.json` в папке мира). Просмотр: кнопка
**Журнал** в GUI (работает и вне заседания) или `/court log`.

### Команды (дублируют GUI)
`/court start [название]`, `end`, `leave`, `list`, `roles`, `role <игрок> <роль>`, `evidence add|list|remove`,
`present <№>`, `testimony add|edit|list|play|clear`, `press <№>`, `object <№> [улика]`,
`verdict guilty|notguilty`, `log`, `protocol`, а также `/aa say <текст>`.

### Мод только на клиенте (сервер на Paper)
Если на сервере нет мода, положи `AceAttorneyRelay-<версия>.jar` из
[релиза](../../releases/latest) в папку `plugins` сервера Paper / Spigot / Purpur. Игроки с модом
смогут проводить суды вместе: плагин пересылает события заседания только тем клиентам, у которых
установлен мод, — в чат ничего не пишется, а игроки без мода ничего не замечают.

В этом режиме мод не добавляет блоки и предметы (на сервере их нет), поэтому места занимаются кнопкой
**Роль** в GUI (`G`). Журнал дел хранится отдельно для каждого сервера у каждого игрока
(`config/aceattorney/relay_cases`). Кто зашёл посреди заседания, получает текущее состояние от судьи
(или другого участника); его протокол начинается с момента входа. Если на сервере нет ни плагина, ни
мода, GUI сообщит, что суд недоступен.

### Свои звуки
Выкрики и молоток озвучены файлами из `src/main/resources/assets/aceattorney/sounds/` —
замени их своими `.ogg` и пересобери мод. Оригинальные ассеты Capcom вкладывать нельзя.

### Сборка
```
./gradlew build                # мод    -> build/libs/
./gradlew -p plugin build      # плагин -> plugin/build/libs/
```
Нужен Java 21.

---

*Неофициальный фанатский проект. «Ace Attorney» — торговая марка Capcom Co., Ltd.
Оригинальные ассеты игр не используются. Лицензия кода — MIT.*
