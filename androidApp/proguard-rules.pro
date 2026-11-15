# 코드 축소(R8) 규칙 (TASK-046). 릴리즈 빌드에서 -Ppillbadeum.minify=true일 때만 쓰임(androidApp/build.gradle.kts).
# 1차 기본은 축소하지 않음(TASK-002).
#
# 지금은 이 앱만의 규칙이 없음. 매니페스트에 적힌 활동·수신기·위젯 공급자는 빌드 도구가 남기고, Room·WorkManager·
# Glance·SQLite(번들)는 라이브러리에 든 규칙이 남김. 축소한 빌드에서 무엇이 빠져 깨지면 그 클래스만 남기는 줄을
# 까닭과 함께 여기에 더함.
