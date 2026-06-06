# Руководство по миграции на FT8MessageClassifier

## Стратегия: Strangler Fig (безопасная миграция)

НЕ делайте big-bang рефакторинг. Двигаемся итеративно.

## Фаза 1: Dual Run (1-2 недели)

Добавьте новый классификатор рядом со старым кодом, логируя расхождения.

### DatabaseOpr.parseMessageState() — добавить в начало метода:

```java
int oldResult = /* старая логика */;
ProtocolStep newStep = FT8MessageClassifier.classify(msg);
int newResult = newStep.getLegacyCode();
if (!toMe && newStep == ProtocolStep.CQ) newResult = 6;
if (oldResult != newResult) {
    Log.w("PARSER_MISMATCH",
        "Old=" + oldResult + " New=" + newResult +
        " extra='" + msg.getExtraInfo() + "' to='" + msg.getCallsignTo() + "'");
}
return oldResult; // пока возвращаем старое!