# Project Guidelines

## Localization

The app supports multiple languages. When writing new UI code, you should localize strings. Follow these steps:

1. Update `app-lang/src/androidMain/res/values/strings.xml` and translations in `values-zh-rTW`,
   `values-zh-rCN` and
   `values-rHK`. Ignore other locales. Ensure you prefix the string keys. For example, if the package name is
   `me.him188.ani.app.ui.settings.mediasource`, you should prefix string keys with "settings_mediasource_".
2. Add import `me.him188.ani.app.ui.lang.*` and
   `org.jetbrains.compose.resources.*` for accessing resources. Note that you should star-import
   `.lang.*`, not just import
   `.lang.Lang`, because the strings are extension propreties on the Lang object.
3. Replace code in Compose using `stringResource(Lang.xxx)`.
   If the hardcoded string is not in compose (an error reported on
   `stringResource`), you may either use the suspend variant
   `getString(Lang.xxx)` if we have a coroutine scope available, or you load the resource into a variable in Composable code, then use it in the non-compose code.
   You can just leave references to `Lang.xxx` red, as it will be generated later.
   Don't translate checks in kotlin `require()`, `check`, and
   `error` functions, like array bound check "selectedItemIndex must be -1 or in the range of items".
