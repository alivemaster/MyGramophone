# Gramophone

My Gramophone. A sane music player based on [Gramophone](https://github.com/FoedusProgramme/Gramophone), with [MyGo Music](https://github.com/egoist/mygo-music) plugin support.

## Installation
You can download the latest stable version of the app from [GitHub releases](https://github.com/alivemaster/MyGramophone/releases/latest).

## Building
To build this app, you will need the latest beta version of [Android Studio](https://developer.android.com/studio) and a fast network.

### 1. Submodules

Gramophone includes certain dependencies such as media3 as git submodule. Make sure you download git submodules by running `git submodule update --init --recursive` before trying to build Gramophone.

### 2. Set up package type
Gramophone has a package type that indicates the source of the application package. Package type string is extracted from an external file named `package.properties`.

Simply open your favorite text editor, type `releaseType=SelfBuilt`, and save it in the root folder of the repository as `package.properties`.

### 3. Start the build
Launch Android Studio and import your own app signing signature. You should be able to build Gramophone now.

## License
This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](https://github.com/alivemaster/MyGramophone/blob/beta/LICENSE) file for details.