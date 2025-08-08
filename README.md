# Minecraft WebAPI - Документация

## 🚀 Обзор
**Minecraft WebAPI** - это библиотека для создания собственных API для Minecraft плагинов. Она предоставляет инструменты для:

- 🌐 Создания веб-сервера внутри вашего плагина
- 🔌 Обработка данных в реальном времени через Server-Sent Events (SSE)
- 📦 Обслуживания статических файлов и API эндпоинтов
- ⚡️ Простой интеграции с JavaScript фронтендом
- 🔒 Автоматической обработки CORS

## 📥 Установка
1. Добавьте файл `WebAPI.java` в ваш проект плагина
2. Поместите файл `minecraft-webapi.js` в папку с вашим веб-сайтом
3. Импортируйте библиотеку в вашем плагине:

```java
import com.yourpackage.web.WebAPI;
```

## 💻 JavaScript библиотека

### Инициализация
```javascript
const api = new MinecraftWebAPI('http://localhost:8080');
```

### Основные методы
| Метод | Описание | Пример |
|-------|----------|--------|
| `fetch(endpoint)` | GET запрос к API | `api.fetch('/players')` |
| `post(endpoint, data)` | POST запрос | `api.post('/send', {message: 'Hello'})` |
| `subscribe(endpoint)` | Подписка на SSE поток | `api.subscribe('/chat')` |
| `on(endpoint, callback)` | Обработчик данных | `api.on('/chat', data => {...})` |
| `autoUpdate(endpoint, ms)` | Автообновление данных | `api.autoUpdate('/stats', 5000)` |
| `disconnect()` | Закрытие соединений | `api.disconnect()` |

## 🧩 Примеры реализации

### Регистрация API в плагине (Java)

```java
public class MyPlugin extends JavaPlugin {
    private WebAPI webApi;

    @Override
    public void onEnable() {
        webApi = new WebAPI(this);
        
        try {
            webApi.start(8080, 10);
        } catch (IOException e) {
            getLogger().severe("Failed to start WebAPI: " + e.getMessage());
            return;
        }

        // Регистрация маршрута для списка игроков
        webApi.addDynamicRoute("/players", () -> {
            JsonArray players = new JsonArray();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(player.getName());
            }
            return players.toString().getBytes(StandardCharsets.UTF_8);
        }, "application/json");

        // Регистрация SSE для чата
        webApi.addSSEEndpoint("/chat");
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onChat(AsyncPlayerChatEvent event) {
                JsonObject message = new JsonObject();
                message.addProperty("sender", event.getPlayer().getName());
                message.addProperty("message", event.getMessage());
                webApi.broadcastToEndpoint("/chat", message.toString());
            }
        }, this);

        // Загрузка фронтенда
        webApi.loadStaticFiles("index.html", "app.js", "style.css");
    }
}
```

### Использование API во фронтенде (JavaScript)

#### Получение списка игроков
```javascript
const api = new MinecraftWebAPI('http://localhost:8080');

api.on('/players', players => {
  const list = document.getElementById('player-list');
  list.innerHTML = players.map(player => `<li>${player}</li>`).join('');
});

api.fetch('/players');
api.autoUpdate('/players', 10000);
```

#### Работа с чатом
```javascript
// Подписка на чат
api.subscribe('/chat');
api.on('/chat', ({sender, message}) => {
  const chat = document.getElementById('chat');
  chat.innerHTML += `<div><b>${sender}:</b> ${message}</div>`;
});

// Отправка сообщения
document.getElementById('send-btn').addEventListener('click', () => {
  const message = document.getElementById('message-input').value;
  
  api.post('/send-message', {message})
    .then(() => console.log('Message sent'))
    .catch(err => console.error('Send error:', err));
});
```

## 🛠️ Расширение функционала

### Создание кастомных маршрутов
```java
// Простой GET маршрут
webApi.addDynamicRoute("/server-info", () -> {
    JsonObject info = new JsonObject();
    info.addProperty("name", getServer().getName());
    info.addProperty("version", getServer().getVersion());
    return info.toString().getBytes(StandardCharsets.UTF_8);
}, "application/json");

// Обработчик POST запросов
webApi.addPostHandler("/teleport", exchange -> {
    // Обработка запроса телепортации
    // Пример: телепортировать игрока к другому игроку
});
```

### Создание SSE эндпоинтов
```java
// Регистрация эндпоинта для событий входа/выхода
webApi.addSSEEndpoint("/player-events");

// Отправка событий
getServer().getPluginManager().registerEvents(new Listener() {
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("type", "join");
        data.addProperty("player", event.getPlayer().getName());
        webApi.broadcastToEndpoint("/player-events", data.toString());
    }
    
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        JsonObject data = new JsonObject();
        data.addProperty("type", "quit");
        data.addProperty("player", event.getPlayer().getName());
        webApi.broadcastToEndpoint("/player-events", data.toString());
    }
}, this);
```

## 📚 Лучшие практики

1. **Безопасность потоков**:
   - Все операции с Bukkit API выполняйте в основном потоке
   - Используйте `Bukkit.getScheduler().runTask()` для синхронизации

2. **Эффективность SSE**:
   - Отправляйте только измененные данные
   - Используйте сжатие для больших объемов данных
   - Регулярно очищайте неактивные соединения

3. **Обработка ошибок**:
   ```java
   webApi.addDynamicRoute("/status", () -> {
       try {
           // Ваш код
       } catch (Exception e) {
           JsonObject error = new JsonObject();
           error.addProperty("error", e.getMessage());
           return error.toString().getBytes(StandardCharsets.UTF_8);
       }
   });
   ```

4. **Производительность**:
   - Кэшируйте статические ответы
   - Ограничивайте частоту обновлений
   - Используйте пул потоков для обработки запросов

## ⚙️ Архитектура WebAPI

### Ключевые компоненты:
1. **HttpServer** - основа веб-сервера
2. **DynamicRoute** - обработчики API эндпоинтов
3. **SSEEndpoint** - управление соединениями реального времени
4. **StaticFiles** - кэширование статических ресурсов
5. **ThreadPool** - пул потоков для обработки запросов

## 📜 Лицензия
**MIT License**  
Свободное использование, модификация и распространение разрешены.

```text
Copyright 2025 NameOfShadow

Разрешается бесплатное использование, копирование, модификация и распространение
данного программного обеспечения и связанной с ним документации.
```

---

<div align="center">
  <p>✨ Создавайте мощные API для ваших Minecraft плагинов!</p>
</div>
