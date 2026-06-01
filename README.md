# 🔗 Distributed Semi-Join — Project #11

> **Môn học:** Cơ Sở Dữ Liệu Phân Tán  
> **Mã sinh viên:** N23DCCN171  
> **Chủ đề:** So sánh hiệu năng Semi-Join vs Standard Join trên hệ phân tán 2 node  
> **Tech stack:** Java 17 · Spring Boot 3.2.5 · Maven · CSV in-memory

---

## 📖 Mô tả

Dự án mô phỏng môi trường **cơ sở dữ liệu phân tán 2 node** độc lập, giao tiếp qua HTTP REST, để so sánh hai chiến lược join:

| Chiến lược | Mô tả |
|---|---|
| **Semi-Join** | Site 2 chỉ gửi **tập EmpID có project** → Site 1 lọc → Site 1 gửi **tập nhỏ** về Site 2 để join |
| **Standard Join (Ship-Whole)** | Site 1 gửi **toàn bộ** bảng employees (10,000 dòng) sang Site 2 để join |

**Dataset:**
- **Site 1** (`port 8081`): `employees.csv` — 10,000 nhân viên *(Orchestrator)*
- **Site 2** (`port 8082`): `assignments.csv` — 50,000 bản ghi phân công *(Worker)*
- Selectivity ≈ **30%** (chỉ 3,000 / 10,000 nhân viên có assignment) → Semi-Join cực kỳ hiệu quả

---

## ⚙️ Yêu cầu hệ thống

| Công cụ | Phiên bản tối thiểu | Kiểm tra |
|---|---|---|
| Java JDK | **17+** | `java -version` |
| Apache Maven | **3.8+** | `mvn -version` |
| OS | Windows 10/11 | — |

> 💡 Nếu chưa cài Maven, xem hướng dẫn tại: https://maven.apache.org/install.html

---

## 📁 Cấu trúc thư mục

```
N23DCCN171/
├── site1-employees/          # Node 1 – port 8081 (Orchestrator)
│   ├── src/main/
│   │   ├── java/com/...      # Controller, Service, Model
│   │   └── resources/
│   │       ├── application.properties
│   │       └── data/
│   │           └── employees.csv   ← 10,000 dòng
│   └── pom.xml
│
├── site2-assignments/        # Node 2 – port 8082 (Worker)
│   ├── src/main/
│   │   ├── java/com/...      # Controller, Service, Model
│   │   └── resources/
│   │       ├── application.properties
│   │       └── data/
│   │           └── assignments.csv  ← 50,000 dòng
│   └── pom.xml
│
├── GenerateData.java         # Script tạo CSV dataset (chạy 1 lần)
├── GenerateData.class        # Bytecode đã compile sẵn
├── employees.csv             # Dataset gốc (backup)
├── assignments.csv           # Dataset gốc (backup)
└── run-all.bat               # Script tự động build + chạy cả 2 node
```

---

## 🚀 Hướng dẫn chạy

### ▶️ Cách 1: Chạy tự động (khuyến nghị)

Mở **Command Prompt** hoặc **File Explorer**, double-click vào:

```
run-all.bat
```

Script sẽ tự động:
1. Tìm Maven (trong PATH hoặc IntelliJ bundled)
2. Build `site2-assignments` → Build `site1-employees`
3. Khởi động Site 2 trước (chờ 15 giây)
4. Khởi động Site 1 (chờ 20 giây)

Sau khi hoàn tất, truy cập các endpoint:

| Chức năng | URL |
|---|---|
| 🔬 Benchmark so sánh | http://localhost:8081/site1/benchmark |
| 🔗 Semi-Join | http://localhost:8081/site1/semijoin |
| 📋 Standard Join | http://localhost:8081/site1/standard-join |
| ℹ️ Site 2 Info | http://localhost:8082/site2/info |

---

### ▶️ Cách 2: Chạy thủ công (2 terminal riêng biệt)

**Terminal 1 — Khởi động Site 2 trước:**
```bash
cd site2-assignments
mvn spring-boot:run
```
Chờ xuất hiện dòng: `Started Site2Application on port 8082`

**Terminal 2 — Sau đó khởi động Site 1:**
```bash
cd site1-employees
mvn spring-boot:run
```
Chờ xuất hiện dòng: `Started Site1Application on port 8081`

> ⚠️ **Quan trọng:** Phải khởi động **Site 2 trước**, vì Site 1 (Orchestrator) sẽ gọi HTTP đến Site 2 ngay khi nhận request.

---

## 🗄️ Deploy Dataset lần đầu

> Dataset CSV đã được **nhúng sẵn vào source code** tại `src/main/resources/data/`.  
> Thông thường **không cần làm gì thêm**. Chỉ thực hiện bước này nếu file CSV bị mất hoặc muốn tái tạo dữ liệu.

### Bước 1: Compile và chạy script sinh dữ liệu

Từ thư mục `N23DCCN171/`, chạy:

```bash
# Compile (nếu chưa có .class)
javac GenerateData.java

# Chạy để sinh 2 file CSV
java GenerateData
```

Kết quả sẽ tạo ra 2 file trong **thư mục hiện tại**:

```
✅ employees.csv    → 10,000 dòng   (EmpID, Name, Department, Salary)
✅ assignments.csv  → 50,000 dòng   (AssignID, EmpID, ProjectID, Role, HoursWorked)
```

### Bước 2: Copy file vào đúng vị trí

```bash
# employees.csv → Site 1
copy employees.csv site1-employees\src\main\resources\data\employees.csv

# assignments.csv → Site 2
copy assignments.csv site2-assignments\src\main\resources\data\assignments.csv
```

Hoặc dùng Windows Explorer:
- Copy `employees.csv` → `site1-employees/src/main/resources/data/`
- Copy `assignments.csv` → `site2-assignments/src/main/resources/data/`

### Bước 3: Build lại project

```bash
cd site1-employees && mvn clean package -DskipTests
cd ..\site2-assignments && mvn clean package -DskipTests
```

---

## 🔌 API Endpoints

### Site 1 — Employees (port 8081)

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/site1/semijoin` | Thực hiện Semi-Join (có thể thêm `?dept=IT`) |
| `GET` | `/site1/standard-join` | Thực hiện Standard Join (ship whole table) |
| `GET` | `/site1/benchmark` | So sánh cả hai phương pháp, trả về metrics đầy đủ |

**Tham số tùy chọn cho Semi-Join:**
```
GET /site1/semijoin?dept=Engineering
GET /site1/semijoin?dept=IT
```

### Site 2 — Assignments (port 8082)

| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/site2/info` | Thông tin dataset (số dòng, distinct EmpID...) |
| `GET` | `/site2/distinct-emp-ids` | Lấy danh sách EmpID có assignment (bước 1 Semi-Join) |
| `POST` | `/site2/join-with-employees` | Nhận employees đã lọc, thực hiện join (bước 3 Semi-Join) |

---

## 📊 Cách đọc kết quả Benchmark

Endpoint `/site1/benchmark` trả về JSON với các chỉ số:

```json
{
  "semiJoinTime_ms": 45,
  "standardJoinTime_ms": 312,
  "semiJoinBytes": 18432,
  "standardBytes": 1048576,
  "SRF": 0.982,
  "totalCost_semiJoin": 1.24,
  "totalCost_standardJoin": 8.91
}
```

| Chỉ số | Ý nghĩa |
|---|---|
| `semiJoinTime_ms` | Thời gian thực thi Semi-Join (milliseconds) |
| `standardJoinTime_ms` | Thời gian thực thi Standard Join (milliseconds) |
| `SRF` | **Size Reduction Factor** = `1 - (semiJoinBytes / standardBytes)` |
| `totalCost_*` | Tổng chi phí = I/O + CPU + Communication (theo mô hình Özsu & Valduriez §5.4) |

> **SRF ≈ 0.98** có nghĩa là Semi-Join chỉ cần truyền **2% lượng dữ liệu** so với Standard Join.

---

## 🔧 Cấu hình

### Site 1 — `site1-employees/src/main/resources/application.properties`
```properties
server.port=8081
site2.url=http://localhost:8082        # Thay đổi nếu Site 2 chạy trên máy khác
spring.mvc.async.request-timeout=60000
```

### Chạy phân tán trên 2 máy khác nhau

Nếu muốn chạy Site 1 và Site 2 trên 2 máy thật:

1. Chạy Site 2 trên máy B (`IP: 192.168.1.x`)
2. Trên Site 1, sửa `application.properties`:
   ```properties
   site2.url=http://192.168.1.x:8082
   ```
3. Đảm bảo firewall mở port 8082 trên máy B

---

## 🧠 Kiến trúc Semi-Join (3 bước)

```
Site 1 (Employees)                    Site 2 (Assignments)
──────────────────                    ────────────────────
                     ← Bước 1 ─────── Gửi: {distinct EmpIDs có assignment}
Lọc employees        Bước 2 ───────→
chỉ giữ EmpID        Bước 3 ─────────→ Nhận: {employees đã lọc} → thực hiện JOIN
có trong tập trên
```

**So sánh lượng dữ liệu truyền:**
- Standard Join: `10,000 × 64 bytes ≈ 640 KB`
- Semi-Join bước 1: `3,000 × 4 bytes ≈ 12 KB`
- Semi-Join bước 3: `3,000 × 64 bytes ≈ 192 KB`
- **Tiết kiệm ≈ 70% bandwidth**

---

## 📚 Tài liệu tham khảo

- Özsu, M.T. & Valduriez, P. — *Principles of Distributed Database Systems* (4th ed.), §5.4 Semi-join Reductions
- Spring Boot 3.2 Documentation: https://docs.spring.io/spring-boot/docs/3.2.5/reference/html/

---

*Dự án Cuối Kỳ — Cơ Sở Dữ Liệu Phân Tán · N23DCCN171*
