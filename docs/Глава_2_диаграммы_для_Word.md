# Диаграммы и таблица для вставки в Word (Глава 2)

Ниже приведены коды диаграмм Mermaid. Чтобы получить картинку для Word:
1. Откройте https://mermaid.live/
2. Вставьте код в левую панель.
3. Справа появится диаграмма. Нажмите **Download PNG** или **Download SVG**.
4. Вставьте изображение в документ Word (Вставка → Рисунок).

Таблицу можно скопировать в Word как обычную таблицу или набрать вручную.

---

## Рисунок 2.2 — Связи между сущностями базы данных

```
erDiagram
    file_attributes ||--o{ model_photo : "model_object_key"
    file_attributes ||--o{ model_video : "model_object_key"
    file_attributes ||--o{ model_slice : "model_object_key"

    file_attributes {
        int file_attributes_id PK
        string file_name
        string content_type
        long size
        string description
        string s3_object_key
    }

    model_photo {
        int id PK
        string model_object_key FK
        string s3_key
        int display_order
    }

    model_video {
        int id PK
        string model_object_key FK
        string s3_key
        int display_order
    }

    model_slice {
        int id PK
        string model_object_key FK
        string s3_key
        string axis
        int slice_index
        int display_order
    }
```

---

## Рисунок 2.4 — Диаграмма последовательности: запрос кадра рендера

```
sequenceDiagram
    participant Client as Клиент
    participant API as REST / WebSocket
    participant Service as RenderService
    participant MinIO as MinIO (OBJ)

    Client->>API: GET /files/{key}/render или WebSocket rotate
    API->>Service: renderModel(objectKey, stream, azimuth, elevation)
    Service->>MinIO: чтение OBJ по s3_object_key
    MinIO-->>Service: поток OBJ
    Service->>Service: рендер (JOGL/LWJGL), JPEG
    Service-->>API: byte[] JPEG
    API-->>Client: JPEG-кадр
```

---

## Рисунок 2.5 — Среда развёртывания (Docker Compose)

```
flowchart LR
    subgraph Docker Host
        subgraph Контейнеры
            App["Atlas App\nSpring Boot :8010"]
            PG[(PostgreSQL\n:5432)]
            MinIO[("MinIO\n:9000 / :9001")]
        end
    end

    App -->|"JPA, JDBC"| PG
    App -->|"S3 API"| MinIO
    Client["Клиент\n(браузер / API)"] -->|"HTTP, WebSocket"| App
```

---

## Таблица 2.1 — Основные методы REST API

| Метод   | Путь | Назначение |
|--------|------|------------|
| GET    | /files | Список всех 3D-моделей |
| GET    | /files/{objectKey} | Информация о модели |
| POST   | /files/upload | Загрузка OBJ-модели |
| DELETE | /files/{objectKey} | Удаление модели и связанных медиа |
| GET    | /files/{objectKey}/render | Статичный кадр рендера (JPEG) |
| GET    | /files/{objectKey}/meta | Метаданные (описание, фото, видео, срезы) |
| PATCH  | /files/{objectKey}/meta | Обновление описания модели |
| POST   | /files/{objectKey}/photos | Загрузка фото к модели |
| POST   | /files/{objectKey}/videos | Загрузка видео к модели |
| POST   | /files/{objectKey}/slices | Загрузка срезов к модели |
| GET    | /files/{objectKey}/media/photo/{id} | Получение изображения фото |
| GET    | /files/{objectKey}/media/video/{id} | Получение видео |
| GET    | /files/{objectKey}/media/slice/{id} | Получение изображения среза |

**WebSocket:** ws://<host>:8010/ws/render/{modelId} — потоковая передача кадров рендера.
