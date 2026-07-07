<div align="center">

<img src="src/main/resources/assets/aceattorney/icon.png" width="96" alt="Ace Attorney Mod">

# Ace Attorney Mod

**Courtroom drama for Minecraft.** Hold real trials with your friends: roles, evidence,
cross-examination, a court clerk keeping the record — and of course full-screen **OBJECTION!** shouts.

<img src="src/main/resources/assets/aceattorney/textures/gui/shout_objection.png" width="360" alt="OBJECTION!">

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
- **Dialogue boxes** — `/aa say <text>` shows a typewriter-style AA dialogue box to players nearby.
- Full command fallback under `/court` for everything the GUI does.

### Installation
1. Install the [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.11**.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and the
   [mod jar](../../releases/latest) into your `mods` folder.
3. The mod is needed on the server **and** on every client.

### Building
```
./gradlew build
```
The jar appears in `build/libs/`.

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

### Журнал дел
Каждое заседание получает сквозной номер. Вердикты записываются автоматически и переживают
перезапуск сервера (файл `aceattorney_case_log.json` в папке мира). Просмотр: кнопка
**Журнал** в GUI (работает и вне заседания) или `/court log`.

### Команды (дублируют GUI)
`/court start [название]`, `end`, `roles`, `role <игрок> <роль>`, `evidence add|list|remove`,
`present <№>`, `testimony add|edit|list|play|clear`, `press <№>`, `object <№> [улика]`,
`verdict guilty|notguilty`, `log`, `protocol`, а также `/aa say <текст>`.

### Свои звуки
Выкрики и молоток озвучены файлами из `src/main/resources/assets/aceattorney/sounds/` —
замени их своими `.ogg` и пересобери мод. Оригинальные ассеты Capcom вкладывать нельзя.

### Сборка
```
./gradlew build
```
Готовый jar — в `build/libs/`. Нужен Java 21.

---

*Неофициальный фанатский проект. «Ace Attorney» — торговая марка Capcom Co., Ltd.
Оригинальные ассеты игр не используются. Лицензия кода — MIT.*
