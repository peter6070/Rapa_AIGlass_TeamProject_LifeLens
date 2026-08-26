/**
 * @license
 * Copyright 2025-2026 Open Home Foundation
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Theme service for managing dark/light mode preferences.
 * Supports three modes: light, dark, and system (auto-detect from OS).
 */

export type ThemePreference = "light" | "dark" | "system";
export type EffectiveTheme = "light" | "dark";

const STORAGE_KEY = "matterTheme";

class ThemeServiceImpl {
    private _preference: ThemePreference = "system";
    private _mediaQuery: MediaQueryList;
    private _listeners: Set<(theme: EffectiveTheme) => void> = new Set();

    constructor() {
        this._mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
        this._mediaQuery.addEventListener("change", () => this._applyTheme());
        this._loadPreference();
        this._applyTheme();
    }

    get preference(): ThemePreference {
        return this._preference;
    }

    get effectiveTheme(): EffectiveTheme {
        if (this._preference === "system") {
            return this._mediaQuery.matches ? "dark" : "light";
        }
        return this._preference;
    }

    setPreference(pref: ThemePreference): void {
        this._preference = pref;
        localStorage.setItem(STORAGE_KEY, pref);
        this._applyTheme();
    }

    cycleTheme(): ThemePreference {
        const cycle: ThemePreference[] = ["light", "dark", "system"];
        const currentIndex = cycle.indexOf(this._preference);
        const nextIndex = (currentIndex + 1) % cycle.length;
        this.setPreference(cycle[nextIndex]);
        return this._preference;
    }

    subscribe(callback: (theme: EffectiveTheme) => void): () => void {
        this._listeners.add(callback);
        return () => this._listeners.delete(callback);
    }

    private _loadPreference(): void {
        // Check for query parameter override (e.g., ?theme=dark)
        const urlParams = new URLSearchParams(window.location.search);
        const themeParam = urlParams.get("theme") as ThemePreference | null;
        if (themeParam && ["light", "dark", "system"].includes(themeParam)) {
            // Use query parameter value and save to localStorage
            this._preference = themeParam;
            localStorage.setItem(STORAGE_KEY, themeParam);
            // Remove the query parameter from URL without reload
            urlParams.delete("theme");
            const newUrl = urlParams.toString()
                ? `${window.location.pathname}?${urlParams.toString()}${window.location.hash}`
                : `${window.location.pathname}${window.location.hash}`;
            history.replaceState({}, "", newUrl);
            return;
        }

        // Fall back to localStorage
        const stored = localStorage.getItem(STORAGE_KEY) as ThemePreference | null;
        if (stored && ["light", "dark", "system"].includes(stored)) {
            this._preference = stored;
        }
        // Default is "system" if nothing stored
    }

    private _applyTheme(): void {
        const effective = this.effectiveTheme;
        document.documentElement.classList.toggle("dark-theme", effective === "dark");

        // Update meta theme-color for mobile browsers
        const metaThemeColor = document.querySelector('meta[name="theme-color"]');
        if (metaThemeColor) {
            metaThemeColor.setAttribute("content", effective === "dark" ? "#1e1e1e" : "#03a9f4");
        }

        this._listeners.forEach(cb => cb(effective));
    }
}

export const ThemeService = new ThemeServiceImpl();
