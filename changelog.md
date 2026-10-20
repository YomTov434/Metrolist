<!--
Release notes for the Gold Communications build.

Entries are keyed by versionCode, not versionName: versionName mirrors the
upstream Metrolist release and stays put across internal updates, so it
cannot identify a release on its own. Add a new ---v<versionCode> block at
the top before every release; .github/workflows/release.yml reads the
matching block and publishes it as the release notes.

Customers read this text on the update screen, so write it for them —
what changed and why it matters — in Hebrew first, then English.
-->

---v112
## תיקונים

- הורדת העדכון כבר לא נעצרת באמצע כשמסובבים את המסך או יוצאים מהמסך. ההורדה ממשיכה ברקע עד הסוף.

---

## Fixes

- Downloading an update no longer stops halfway when you rotate the screen or leave the screen. It keeps going in the background.

---v111
## מה חדש

- אפשר לעבור בין הגרסה הרגילה לגרסה עם Google Cast, ישירות מתוך מסך "אודות". הספרייה וההגדרות נשמרות במעבר.

---

## What's new

- Switch between the standard build and the one with Google Cast straight from the About screen. Your library and settings are kept.

---v110
## מה חדש

- מסך "אודות" קוצר והשאיר רק את פרטי האפליקציה.

## שיפורים ותיקונים

- תיקוני ניגון: עצירה מלאה של ההשמעה ביציאה מהאפליקציה, שמירת סדר הערבוב, ושליטה טובה יותר מאוזניות ומרכב.
- תיקוני חיפוש: תוצאות מדויקות יותר ושמות אמנים תקינים.
- תיקוני סנכרון: שירים שנמחקו מהחשבון כבר לא חוזרים מעצמם.
- שיפורי יציבות וביצועים בספרייה, בהורדות ובמילות השירים.

---

## What's new

- The About screen now shows just the app details.

## Fixes and improvements

- Playback: leaving the app stops playback properly, shuffle order is kept, and external controls behave.
- Search: more accurate results and correct artist names.
- Sync: songs removed from your account no longer come back on their own.
- Stability and performance work across the library, downloads and lyrics.
