#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winsock2.h>
#include <ws2tcpip.h>
#include <ws2bth.h>
#include <bluetoothapis.h>

#include <winrt/Windows.Data.Json.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Media.Core.h>
#include <winrt/Windows.Media.Playback.h>
#include <winrt/Windows.Security.Cryptography.h>
#include <winrt/Windows.Storage.Streams.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cwctype>
#include <iostream>
#include <mutex>
#include <optional>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "Bthprops.lib")
#pragma comment(lib, "windowsapp.lib")

using namespace winrt;
using namespace Windows::Data::Json;
using namespace Windows::Foundation;
using namespace Windows::Media;
using namespace Windows::Media::Core;
using namespace Windows::Media::Playback;
using namespace Windows::Security::Cryptography;
using namespace Windows::Storage::Streams;

namespace {
constexpr int kProtocolVersion = 1;
constexpr uint16_t kDefaultPort = 45831;
constexpr GUID kRfcommServiceUuid = {
    0x8e7f1a9d, 0x2c64, 0x4db8, {0x9f, 0x75, 0x6a, 0x33, 0xce, 0x5b, 0x21, 0x70}
};

std::atomic_bool g_running{true};
std::atomic<SOCKET> g_activeSocket{INVALID_SOCKET};

std::wstring widen(const std::string& text) {
    if (text.empty()) return {};
    int size = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
    std::wstring result(size, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), result.data(), size);
    return result;
}

std::string narrow(const std::wstring& text) {
    if (text.empty()) return {};
    int size = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    std::string result(size, '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), result.data(), size, nullptr, nullptr);
    return result;
}

class SocketConnection {
public:
    SocketConnection() = default;
    SocketConnection(const SocketConnection&) = delete;
    SocketConnection& operator=(const SocketConnection&) = delete;
    ~SocketConnection() { close(); }

    void connectWifi(const std::string& host, uint16_t port) {
        addrinfo hints{};
        hints.ai_family = AF_UNSPEC;
        hints.ai_socktype = SOCK_STREAM;
        hints.ai_protocol = IPPROTO_TCP;
        addrinfo* addresses = nullptr;
        const std::string service = std::to_string(port);
        if (getaddrinfo(host.c_str(), service.c_str(), &hints, &addresses) != 0) {
            throw std::runtime_error("cannot resolve Wi-Fi host");
        }
        for (auto* address = addresses; address; address = address->ai_next) {
            socket_ = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
            if (socket_ == INVALID_SOCKET) continue;
            if (::connect(socket_, address->ai_addr, static_cast<int>(address->ai_addrlen)) == 0) break;
            closesocket(socket_);
            socket_ = INVALID_SOCKET;
        }
        freeaddrinfo(addresses);
        if (socket_ == INVALID_SOCKET) throw std::runtime_error("cannot connect to Android over Wi-Fi");
        g_activeSocket = socket_;
    }

    void connectBluetooth(const std::string& selector) {
        std::vector<BTH_ADDR> candidates;
        if (looksLikeBluetoothAddress(selector)) {
            candidates.push_back(parseBluetoothAddress(selector));
        } else {
            candidates = findPairedBluetoothDevices(selector);
        }
        if (candidates.empty()) {
            throw std::runtime_error("no paired Bluetooth device matched '" + selector + "'");
        }
        int lastError = 0;
        for (const auto address : candidates) {
            socket_ = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
            if (socket_ == INVALID_SOCKET) {
                lastError = WSAGetLastError();
                continue;
            }
            SOCKADDR_BTH target{};
            target.addressFamily = AF_BTH;
            target.btAddr = address;
            target.serviceClassId = kRfcommServiceUuid;
            target.port = BT_PORT_ANY;
            if (::connect(socket_, reinterpret_cast<sockaddr*>(&target), sizeof(target)) == 0) {
                g_activeSocket = socket_;
                return;
            }
            lastError = WSAGetLastError();
            closesocket(socket_);
            socket_ = INVALID_SOCKET;
        }
        throw std::runtime_error("Bluetooth RFCOMM service was not reachable (Winsock " + std::to_string(lastError) + ")");
    }

    bool sendLine(const std::string& line) {
        std::scoped_lock lock(sendMutex_);
        std::string frame = line + "\n";
        size_t sent = 0;
        while (sent < frame.size()) {
            int count = send(socket_, frame.data() + sent, static_cast<int>(frame.size() - sent), 0);
            if (count <= 0) return false;
            sent += static_cast<size_t>(count);
        }
        return true;
    }

    bool readLine(std::string& line) {
        line.clear();
        while (true) {
            const auto newline = pending_.find('\n');
            if (newline != std::string::npos) {
                line = pending_.substr(0, newline);
                pending_.erase(0, newline + 1);
                if (!line.empty() && line.back() == '\r') line.pop_back();
                return true;
            }
            char buffer[8192];
            int count = recv(socket_, buffer, sizeof(buffer), 0);
            if (count <= 0) return false;
            pending_.append(buffer, static_cast<size_t>(count));
            if (pending_.size() > 8 * 1024 * 1024) throw std::runtime_error("incoming frame exceeds 8 MiB");
        }
    }

    void close() {
        if (socket_ != INVALID_SOCKET) {
            shutdown(socket_, SD_BOTH);
            closesocket(socket_);
            SOCKET expected = socket_;
            g_activeSocket.compare_exchange_strong(expected, INVALID_SOCKET);
            socket_ = INVALID_SOCKET;
        }
    }

private:
    static bool looksLikeBluetoothAddress(std::string text) {
        text.erase(std::remove_if(text.begin(), text.end(), [](unsigned char c) {
            return c == ':' || c == '-' || std::isspace(c);
        }), text.end());
        return text.size() == 12 &&
            std::all_of(text.begin(), text.end(), [](unsigned char c) { return std::isxdigit(c); });
    }

    static BTH_ADDR parseBluetoothAddress(std::string text) {
        text.erase(std::remove_if(text.begin(), text.end(), [](unsigned char c) {
            return c == ':' || c == '-' || std::isspace(c);
        }), text.end());
        if (text.size() != 12 || !std::all_of(text.begin(), text.end(), [](unsigned char c) { return std::isxdigit(c); })) {
            throw std::runtime_error("Bluetooth address must look like AA:BB:CC:DD:EE:FF");
        }
        return std::stoull(text, nullptr, 16);
    }

    static std::vector<BTH_ADDR> findPairedBluetoothDevices(const std::string& selector) {
        std::vector<BTH_ADDR> result;
        BLUETOOTH_DEVICE_SEARCH_PARAMS search{};
        search.dwSize = sizeof(search);
        search.fReturnAuthenticated = TRUE;
        search.fReturnRemembered = TRUE;
        search.fReturnConnected = TRUE;
        search.fReturnUnknown = FALSE;
        search.fIssueInquiry = FALSE;
        BLUETOOTH_DEVICE_INFO device{};
        device.dwSize = sizeof(device);
        HBLUETOOTH_DEVICE_FIND handle = BluetoothFindFirstDevice(&search, &device);
        if (!handle) return result;
        const std::wstring wanted = widen(selector);
        do {
            std::wstring name = device.szName;
            auto lower = [](std::wstring value) {
                std::transform(value.begin(), value.end(), value.begin(), [](wchar_t c) {
                    return static_cast<wchar_t>(std::towlower(c));
                });
                return value;
            };
            if (selector == "auto" || lower(name).find(lower(wanted)) != std::wstring::npos) {
                result.push_back(device.Address.ullLong);
            }
            device = {};
            device.dwSize = sizeof(device);
        } while (BluetoothFindNextDevice(handle, &device));
        BluetoothFindDeviceClose(handle);
        return result;
    }

    SOCKET socket_ = INVALID_SOCKET;
    std::mutex sendMutex_;
    std::string pending_;
};

struct RemoteState {
    std::wstring app;
    std::wstring title;
    std::wstring artist;
    std::wstring album;
    std::wstring playback;
    std::wstring artBase64;
    int64_t durationMs = 0;
    int64_t positionMs = 0;
    bool canPlay = false;
    bool canPause = false;
    bool canNext = false;
    bool canPrevious = false;
    bool canSeek = false;
};

TimeSpan milliseconds(int64_t value) {
    return std::chrono::duration_cast<TimeSpan>(std::chrono::milliseconds(std::max<int64_t>(value, 0)));
}

std::vector<uint8_t> silentWav() {
    constexpr uint32_t sampleRate = 8000;
    constexpr uint16_t channels = 1;
    constexpr uint16_t bits = 16;
    constexpr uint32_t dataSize = sampleRate * channels * (bits / 8);
    std::vector<uint8_t> bytes(44 + dataSize, 0);
    auto put16 = [&](size_t at, uint16_t value) {
        bytes[at] = static_cast<uint8_t>(value); bytes[at + 1] = static_cast<uint8_t>(value >> 8);
    };
    auto put32 = [&](size_t at, uint32_t value) {
        for (int i = 0; i < 4; ++i) bytes[at + i] = static_cast<uint8_t>(value >> (i * 8));
    };
    std::copy_n("RIFF", 4, bytes.begin());
    put32(4, 36 + dataSize);
    std::copy_n("WAVEfmt ", 8, bytes.begin() + 8);
    put32(16, 16); put16(20, 1); put16(22, channels); put32(24, sampleRate);
    put32(28, sampleRate * channels * (bits / 8)); put16(32, channels * (bits / 8)); put16(34, bits);
    std::copy_n("data", 4, bytes.begin() + 36); put32(40, dataSize);
    return bytes;
}

class SmtcPublisher {
public:
    explicit SmtcPublisher(SocketConnection& connection)
        : connection_(connection), player_(), controls_(player_.SystemMediaTransportControls()) {
        player_.CommandManager().IsEnabled(false);
        player_.IsLoopingEnabled(true);
        player_.Volume(0.0);
        controls_.IsEnabled(false);
        controls_.ButtonPressed([this](auto const&, SystemMediaTransportControlsButtonPressedEventArgs const& args) {
            switch (args.Button()) {
                case SystemMediaTransportControlsButton::Play: sendCommand("play"); break;
                case SystemMediaTransportControlsButton::Pause: sendCommand("pause"); break;
                case SystemMediaTransportControlsButton::Next: sendCommand("next"); break;
                case SystemMediaTransportControlsButton::Previous: sendCommand("previous"); break;
                case SystemMediaTransportControlsButton::Stop: sendCommand("stop"); break;
                default: break;
            }
        });
        controls_.PlaybackPositionChangeRequested([this](auto const&, PlaybackPositionChangeRequestedEventArgs const& args) {
            const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(args.RequestedPlaybackPosition()).count();
            sendCommand("seek", ms);
        });
        prepareSilentSource();
    }

    void update(const RemoteState& state) {
        controls_.IsEnabled(!state.title.empty());
        controls_.IsPlayEnabled(state.canPlay);
        controls_.IsPauseEnabled(state.canPause);
        controls_.IsNextEnabled(state.canNext);
        controls_.IsPreviousEnabled(state.canPrevious);
        controls_.IsStopEnabled(true);

        auto updater = controls_.DisplayUpdater();
        updater.Type(MediaPlaybackType::Music);
        auto music = updater.MusicProperties();
        music.Title(state.title);
        music.Artist(state.artist);
        music.AlbumTitle(state.album);
        if (!state.artBase64.empty()) {
            try {
                auto buffer = CryptographicBuffer::DecodeFromBase64String(state.artBase64);
                InMemoryRandomAccessStream stream;
                stream.WriteAsync(buffer).get();
                stream.Seek(0);
                updater.Thumbnail(RandomAccessStreamReference::CreateFromStream(stream));
            } catch (...) {
                updater.Thumbnail(nullptr);
            }
        } else {
            updater.Thumbnail(nullptr);
        }
        updater.Update();

        SystemMediaTransportControlsTimelineProperties timeline;
        timeline.StartTime(milliseconds(0));
        timeline.MinSeekTime(milliseconds(0));
        timeline.Position(milliseconds(state.positionMs));
        timeline.MaxSeekTime(milliseconds(state.durationMs));
        timeline.EndTime(milliseconds(state.durationMs));
        controls_.UpdateTimelineProperties(timeline);

        if (state.playback == L"playing") controls_.PlaybackStatus(MediaPlaybackStatus::Playing);
        else if (state.playback == L"paused") controls_.PlaybackStatus(MediaPlaybackStatus::Paused);
        else controls_.PlaybackStatus(MediaPlaybackStatus::Stopped);
    }

private:
    void prepareSilentSource() {
        auto bytes = silentWav();
        silentStream_ = InMemoryRandomAccessStream();
        DataWriter writer(silentStream_);
        writer.WriteBytes(bytes);
        writer.StoreAsync().get();
        writer.DetachStream();
        silentStream_.Seek(0);
        player_.Source(MediaSource::CreateFromStream(silentStream_, L"audio/wav"));
        player_.Play();
    }

    void sendCommand(const char* action, std::optional<int64_t> position = std::nullopt) {
        JsonObject object;
        object.SetNamedValue(L"type", JsonValue::CreateStringValue(L"command"));
        object.SetNamedValue(L"action", JsonValue::CreateStringValue(widen(action)));
        if (position) object.SetNamedValue(L"positionMs", JsonValue::CreateNumberValue(static_cast<double>(*position)));
        connection_.sendLine(narrow(object.Stringify().c_str()));
    }

    SocketConnection& connection_;
    MediaPlayer player_;
    SystemMediaTransportControls controls_;
    InMemoryRandomAccessStream silentStream_{nullptr};
};

RemoteState parseState(const JsonObject& json) {
    RemoteState state;
    state.app = json.GetNamedString(L"app", L"").c_str();
    state.title = json.GetNamedString(L"title", L"").c_str();
    state.artist = json.GetNamedString(L"artist", L"").c_str();
    state.album = json.GetNamedString(L"album", L"").c_str();
    state.playback = json.GetNamedString(L"playback", L"stopped").c_str();
    state.artBase64 = json.GetNamedString(L"artBase64", L"").c_str();
    state.durationMs = static_cast<int64_t>(json.GetNamedNumber(L"durationMs", 0));
    state.positionMs = static_cast<int64_t>(json.GetNamedNumber(L"positionMs", 0));
    state.canPlay = json.GetNamedBoolean(L"canPlay", false);
    state.canPause = json.GetNamedBoolean(L"canPause", false);
    state.canNext = json.GetNamedBoolean(L"canNext", false);
    state.canPrevious = json.GetNamedBoolean(L"canPrevious", false);
    state.canSeek = json.GetNamedBoolean(L"canSeek", false);
    return state;
}

struct Options {
    std::string wifiHost;
    std::string bluetoothMac;
    std::string pin;
    uint16_t port = kDefaultPort;
};

void printUsage() {
    std::cout
        << "Android SMTC Bridge 1.0\n\n"
        << "Wi-Fi:\n  smtc-bridge.exe --wifi 192.168.1.23 --pin 123456 [--port 45831]\n\n"
        << "Bluetooth (pair the phone in Windows first):\n"
        << "  smtc-bridge.exe --bluetooth auto --pin 123456\n"
        << "  smtc-bridge.exe --bluetooth PHONE_NAME --pin 123456\n"
        << "  smtc-bridge.exe --bluetooth AA:BB:CC:DD:EE:FF --pin 123456\n";
}

Options parseOptions(int argc, char** argv) {
    Options options;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto value = [&]() -> std::string {
            if (++i >= argc) throw std::runtime_error("missing value after " + arg);
            return argv[i];
        };
        if (arg == "--wifi") options.wifiHost = value();
        else if (arg == "--bluetooth") options.bluetoothMac = value();
        else if (arg == "--pin") options.pin = value();
        else if (arg == "--port") options.port = static_cast<uint16_t>(std::stoul(value()));
        else if (arg == "--help" || arg == "-h") { printUsage(); std::exit(0); }
        else throw std::runtime_error("unknown option: " + arg);
    }
    if ((options.wifiHost.empty() == options.bluetoothMac.empty()) || options.pin.size() != 6) {
        throw std::runtime_error("choose exactly one transport and provide a 6-digit PIN");
    }
    if (!options.wifiHost.empty()) {
        const auto colon = options.wifiHost.rfind(':');
        if (colon != std::string::npos && options.wifiHost.find(':') == colon) {
            const auto portText = options.wifiHost.substr(colon + 1);
            if (!portText.empty() && std::all_of(portText.begin(), portText.end(), [](unsigned char c) { return std::isdigit(c); })) {
                const auto parsedPort = std::stoul(portText);
                if (parsedPort == 0 || parsedPort > 65535) throw std::runtime_error("Wi-Fi port is out of range");
                options.port = static_cast<uint16_t>(parsedPort);
                options.wifiHost.erase(colon);
            }
        }
    }
    return options;
}

BOOL WINAPI consoleHandler(DWORD signal) {
    if (signal == CTRL_C_EVENT || signal == CTRL_CLOSE_EVENT || signal == CTRL_BREAK_EVENT) {
        g_running = false;
        const SOCKET active = g_activeSocket.load();
        if (active != INVALID_SOCKET) shutdown(active, SD_BOTH);
        return TRUE;
    }
    return FALSE;
}
} // namespace

int main(int argc, char** argv) {
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCtrlHandler(consoleHandler, TRUE);
    WSADATA winsock{};
    if (WSAStartup(MAKEWORD(2, 2), &winsock) != 0) {
        std::cerr << "Winsock initialization failed\n";
        return 1;
    }
    try {
        init_apartment(apartment_type::multi_threaded);
        const auto options = parseOptions(argc, argv);
        SocketConnection connection;
        if (!options.wifiHost.empty()) {
            std::cout << "Connecting over Wi-Fi to " << options.wifiHost << ':' << options.port << "...\n";
            connection.connectWifi(options.wifiHost, options.port);
        } else {
            std::cout << "Connecting over Bluetooth to " << options.bluetoothMac << "...\n";
            connection.connectBluetooth(options.bluetoothMac);
        }
        JsonObject hello;
        hello.SetNamedValue(L"type", JsonValue::CreateStringValue(L"hello"));
        hello.SetNamedValue(L"version", JsonValue::CreateNumberValue(kProtocolVersion));
        hello.SetNamedValue(L"pin", JsonValue::CreateStringValue(widen(options.pin)));
        if (!connection.sendLine(narrow(hello.Stringify().c_str()))) throw std::runtime_error("handshake send failed");

        SmtcPublisher publisher(connection);
        std::cout << "Connected. Waiting for Android media; press Ctrl+C to stop.\n";
        std::string line;
        while (g_running && connection.readLine(line)) {
            try {
                auto json = JsonObject::Parse(widen(line));
                const auto type = json.GetNamedString(L"type", L"");
                if (type == L"error") throw std::runtime_error(narrow(json.GetNamedString(L"message", L"remote error").c_str()));
                if (type != L"state") continue;
                auto state = parseState(json);
                publisher.update(state);
                std::cout << "\r[" << narrow(state.playback) << "] " << narrow(state.app) << " — " << narrow(state.title) << "                    " << std::flush;
            } catch (const hresult_error& error) {
                std::cerr << "\nInvalid bridge message: " << narrow(error.message().c_str()) << '\n';
            }
        }
        std::cout << "\nDisconnected.\n";
        connection.close();
        uninit_apartment();
    } catch (const hresult_error& error) {
        std::cerr << "Windows error: " << narrow(error.message().c_str()) << '\n';
        WSACleanup();
        return 1;
    } catch (const std::exception& error) {
        std::cerr << "Error: " << error.what() << '\n';
        printUsage();
        WSACleanup();
        return 1;
    }
    WSACleanup();
    return 0;
}
