// PocketIDE's bridge to tree-sitter: parses a source file and returns the names it defines, using one query per language.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "tree_sitter/api.h"

const TSLanguage *tree_sitter_javascript(void);
const TSLanguage *tree_sitter_python(void);
const TSLanguage *tree_sitter_java(void);
const TSLanguage *tree_sitter_kotlin(void);

typedef struct { const char *lang; const TSLanguage *(*fn)(void); const char *query; } Entry;

static const Entry ENTRIES[] = {
    { "javascript", tree_sitter_javascript,
      "(function_declaration name: (identifier) @name) (class_declaration name: (identifier) @name) "
      "(lexical_declaration (variable_declarator name: (identifier) @name value: (arrow_function)))" },
    { "python", tree_sitter_python,
      "(function_definition name: (identifier) @name) (class_definition name: (identifier) @name)" },
    { "java", tree_sitter_java,
      "(method_declaration name: (identifier) @name) (class_declaration name: (identifier) @name) (interface_declaration name: (identifier) @name)" },
    { "kotlin", tree_sitter_kotlin,
      "(function_declaration (simple_identifier) @name) (class_declaration (type_identifier) @name) (object_declaration (type_identifier) @name)" },
};

static char *out = NULL;
static size_t cap = 0, len = 0;

static void put(const char *s, size_t n) {
    if (len + n + 2 > cap) {
        size_t want = (len + n + 2) * 2 + 256;
        char *grown = realloc(out, want);
        if (!grown) return;
        out = grown; cap = want;
    }
    memcpy(out + len, s, n); len += n;
    out[len++] = '\n'; out[len] = 0;
}

JNIEXPORT jstring JNICALL Java_dev_pocketide_app_core_TreeSitter_names(JNIEnv *env, jclass cls, jstring jlang, jstring jsrc) {
    (void)cls;
    const char *lang = (*env)->GetStringUTFChars(env, jlang, NULL);
    const char *src = (*env)->GetStringUTFChars(env, jsrc, NULL);
    len = 0; if (out) out[0] = 0;
    const Entry *e = NULL;
    for (size_t i = 0; i < sizeof(ENTRIES) / sizeof(ENTRIES[0]); i++) if (strcmp(ENTRIES[i].lang, lang) == 0) e = &ENTRIES[i];
    if (e && src) {
        const TSLanguage *language = e->fn();
        TSParser *parser = ts_parser_new();
        if (ts_parser_set_language(parser, language)) {
            size_t n = strlen(src);
            TSTree *tree = ts_parser_parse_string(parser, NULL, src, (uint32_t)n);
            uint32_t err_off = 0; TSQueryError err_type = TSQueryErrorNone;
            TSQuery *q = ts_query_new(language, e->query, (uint32_t)strlen(e->query), &err_off, &err_type);
            if (tree && q) {
                TSQueryCursor *cursor = ts_query_cursor_new();
                ts_query_cursor_exec(cursor, q, ts_tree_root_node(tree));
                TSQueryMatch match;
                while (ts_query_cursor_next_match(cursor, &match)) {
                    for (uint16_t i = 0; i < match.capture_count; i++) {
                        TSNode node = match.captures[i].node;
                        uint32_t start = ts_node_start_byte(node), end = ts_node_end_byte(node);
                        if (end > start && end <= n) put(src + start, end - start);
                    }
                }
                ts_query_cursor_delete(cursor);
            }
            if (q) ts_query_delete(q);
            if (tree) ts_tree_delete(tree);
        }
        ts_parser_delete(parser);
    }
    jstring result = (*env)->NewStringUTF(env, out ? out : "");
    (*env)->ReleaseStringUTFChars(env, jlang, lang);
    (*env)->ReleaseStringUTFChars(env, jsrc, src);
    return result;
}
