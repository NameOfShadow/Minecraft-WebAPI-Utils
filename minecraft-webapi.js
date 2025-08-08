class MinecraftWebAPI {
    constructor(baseUrl = '') {
        this.baseUrl = baseUrl;
        this.eventSources = new Map();
        this.subscriptions = new Map();
        this.cache = {
            players: [],
            status: null
        };
    }

    // ==================== Основные методы ====================
    
    /**
     * Подключается к SSE потоку и автоматически парсит данные
     * @param {string} endpoint - Эндпоинт SSE
     * @param {string} eventType - Тип события (message по умолчанию)
     * @param {Object} [options] - Дополнительные опции
     */
    subscribe(endpoint, eventType = 'message', options = {}) {
        const fullUrl = this.baseUrl + endpoint;
        const eventSource = new EventSource(fullUrl);
        
        eventSource.addEventListener(eventType, (event) => {
            try {
                const data = JSON.parse(event.data);
                this._notifySubscribers(endpoint, data);
            } catch (e) {
                console.error(`Error parsing ${endpoint} data:`, e);
            }
        });

        eventSource.onerror = (error) => {
            console.error(`SSE error on ${endpoint}:`, error);
            if (options.autoReconnect !== false) {
                setTimeout(() => this.subscribe(endpoint, eventType, options), 
                          options.reconnectDelay || 3000);
            }
        };

        this.eventSources.set(endpoint, {source: eventSource, eventType});
    }

    /**
     * Регистрирует обработчик для эндпоинта
     * @param {string} endpoint - Эндпоинт
     * @param {Function} callback - Функция-обработчик
     */
    on(endpoint, callback) {
        if (!this.subscriptions.has(endpoint)) {
            this.subscriptions.set(endpoint, new Set());
        }
        this.subscriptions.get(endpoint).add(callback);
    }

    /**
     * Удаляет обработчик для эндпоинта
     * @param {string} endpoint - Эндпоинт
     * @param {Function} callback - Функция-обработчик
     */
    off(endpoint, callback) {
        const handlers = this.subscriptions.get(endpoint);
        if (handlers) {
            handlers.delete(callback);
        }
    }

    /**
     * Выполняет GET запрос и кэширует результат
     * @param {string} endpoint - Эндпоинт API
     * @returns {Promise} Promise с ответом сервера
     */
    async fetch(endpoint) {
        try {
            const url = this.baseUrl + endpoint;
            const response = await fetch(url);
            
            if (!response.ok) throw new Error(`Request failed: ${response.status}`);
            
            const data = await response.json();
            this.cache[endpoint] = data;
            this._notifySubscribers(endpoint, data);
            return data;
        } catch (error) {
            console.error(`Fetch error for ${endpoint}:`, error);
            throw error;
        }
    }

    /**
     * Выполняет POST запрос
     * @param {string} endpoint - Эндпоинт API
     * @param {Object} data - Данные для отправки
     * @returns {Promise} Promise с ответом сервера
     */
    async post(endpoint, data) {
        const url = this.baseUrl + endpoint;
        const response = await fetch(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(data)
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`POST failed: ${response.status} - ${errorText}`);
        }

        return response.json();
    }

    // ==================== Вспомогательные методы ====================
    
    /**
     * Инициализирует автоматическое обновление данных
     * @param {string} endpoint - Эндпоинт
     * @param {number} interval - Интервал обновления в мс
     */
    autoUpdate(endpoint, interval) {
        this.fetch(endpoint);
        return setInterval(() => this.fetch(endpoint), interval);
    }

    /**
     * Возвращает кэшированные данные
     * @param {string} endpoint - Эндпоинт
     * @returns {any} Кэшированные данные
     */
    getCache(endpoint) {
        return this.cache[endpoint];
    }

    // Закрывает все соединения
    disconnect() {
        this.eventSources.forEach(({source}) => source.close());
        this.eventSources.clear();
        this.subscriptions.clear();
    }

    // ==================== Специфичные для Minecraft методы ====================
    
    /**
     * Отправляет сообщение в игровой чат
     * @param {string} message - Текст сообщения
     */
    sendMessage(message) {
        return this.post('/send-message', {message});
    }

    /**
     * Создает плавающий текст
     * @param {string} player - Имя игрока
     */
    createText(player) {
        return this.post('/create-text', {player});
    }

    /**
     * Обновляет плавающий текст
     * @param {string} uuid - UUID текста
     */
    updateText(uuid) {
        return this.post('/update-text', {uuid});
    }

    // ==================== Приватные методы ====================
    
    _notifySubscribers(endpoint, data) {
        const handlers = this.subscriptions.get(endpoint);
        if (handlers) {
            handlers.forEach(callback => callback(data));
        }
    }
}

// Экспорт для различных сред
if (typeof module !== 'undefined' && typeof module.exports !== 'undefined') {
    module.exports = MinecraftWebAPI;
} else {
    window.MinecraftWebAPI = MinecraftWebAPI;
}