package ru.servermove.app.core

object Shell {
    private val safeDatabaseName = Regex("^[A-Za-z0-9_-]{1,63}$")

    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL недопустим в аргументе shell" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun requireAbsolutePath(path: String, fieldName: String): String {
        require(path.startsWith('/')) { "$fieldName должен быть абсолютным Linux-путём" }
        require('\n' !in path && '\r' !in path) { "$fieldName содержит недопустимый перевод строки" }
        require(path.length <= 4096) { "$fieldName слишком длинный" }
        return path
    }

    fun requireDatabaseName(name: String, fieldName: String): String {
        require(safeDatabaseName.matches(name)) {
            "$fieldName: разрешены только латинские буквы, цифры, _ и - (до 63 символов)"
        }
        return name
    }
}
