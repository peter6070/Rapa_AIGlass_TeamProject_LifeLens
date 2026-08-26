import babel from "@rollup/plugin-babel";
import commonjs from "@rollup/plugin-commonjs";
import copy from "rollup-plugin-copy";
import json from "@rollup/plugin-json";
import nodeResolve from "@rollup/plugin-node-resolve";
import terser from "@rollup/plugin-terser";

const config = {
    input: "dist/esm/entrypoint/main.js",
    output: {
        dir: "dist/web/js",
        format: "module",
    },
    preserveEntrySignatures: false,
    plugins: [
        commonjs(),
        nodeResolve({
            browser: true,
            preferBuiltins: false,
        }),
        babel({
            babelHelpers: "bundled",
            presets: [
                [
                    "@babel/preset-env",
                    {
                        targets: {
                            chrome: "84",
                        },
                    },
                ],
            ],
        }),
        json(),
        copy({
            targets: [{ src: "public/*", dest: "dist/web" }],
        }),
    ],
};

if (process.env.NODE_ENV === "production") {
    config.plugins.push(
        terser({
            ecma: 2019,
            toplevel: true,
            format: {
                comments: false,
            },
        }),
    );
}

export default config;
