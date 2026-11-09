package com.luca.geelykeyradar;

/**
 * 本车蓝牙钥匙身份。
 *
 * 这些字段全部在运行时从广播/官方 App 连接事件学习，不依赖 adb、REA 或用户手填 VIN。
 */
final class VehicleIdentity {

    final String address;
    final String name;
    final String vinTail2;
    final String vinTail3;
    final String mfgSignature;
    final long learnedAtMs;

    VehicleIdentity(
            String address,
            String name,
            String vinTail2,
            String vinTail3,
            String mfgSignature,
            long learnedAtMs
    ) {
        this.address = address == null ? "" : address;
        this.name = name == null ? "" : name;
        this.vinTail2 = vinTail2 == null ? "" : vinTail2;
        this.vinTail3 = vinTail3 == null ? "" : vinTail3;
        this.mfgSignature = mfgSignature == null ? "" : mfgSignature;
        this.learnedAtMs = learnedAtMs;
    }

    static VehicleIdentity from(GeelyAdvertisement ad) {
        if (ad == null) return null;
        String t3 = ad.vinTail3;
        String t2 = ad.vinTail2;
        if (t2.isEmpty() && t3.length() >= 2) {
            t2 = t3.substring(t3.length() - 2);
        }
        return new VehicleIdentity(
                ad.address,
                ad.name,
                t2,
                t3,
                ad.mfgSignature,
                System.currentTimeMillis()
        );
    }

    /**
     * 判断一条广播是否属于本车。
     *
     * 地址可能随车机蓝牙模块重启而变化，所以名称/VIN 尾号/厂商数据签名都是备用身份。
     * 只要地址、名称、VIN 后三位或 VIN 后两位（无冲突车名）命中，即判定为本车。
     */
    boolean matches(GeelyAdvertisement ad) {
        if (ad == null) return false;
        if (!address.isEmpty() && address.equalsIgnoreCase(ad.address)) {
            return true;
        }

        // 如果广播带了明确的 GeelyVehicle* 名称，且与本车已学习的名称或尾号冲突，直接排除
        if (!ad.name.isEmpty() && GeelyAdvertisement.isGeelyVehicleName(ad.name)) {
            if (!name.isEmpty() && !name.equalsIgnoreCase(ad.name)) {
                return false;
            }
            if (!vinTail3.isEmpty() && !ad.vinTail3.isEmpty() && !vinTail3.equalsIgnoreCase(ad.vinTail3)) {
                return false;
            }
        }

        boolean nameOk = !name.isEmpty() && name.equalsIgnoreCase(ad.name);
        boolean tail3Ok = !vinTail3.isEmpty() && vinTail3.equalsIgnoreCase(ad.vinTail3);
        String effectiveTail2 = !vinTail2.isEmpty()
                ? vinTail2
                : (vinTail3.length() >= 2 ? vinTail3.substring(vinTail3.length() - 2) : "");
        boolean tail2Ok = !effectiveTail2.isEmpty() && effectiveTail2.equalsIgnoreCase(ad.vinTail2);
        boolean sigOk = !mfgSignature.isEmpty() && mfgSignature.equalsIgnoreCase(ad.mfgSignature);

        if (nameOk) return true;
        if (tail3Ok) return true;
        if (tail2Ok) return true;
        if (sigOk && ad.name.isEmpty()) return true;
        return false;
    }

    /**
     * 将新广播中补全的字段（如 ScanResponse 名称、01FE 尾号、最新 MAC）合并到本车身份中。
     */
    VehicleIdentity mergeWith(GeelyAdvertisement ad) {
        if (ad == null) return this;
        String newAddr = !ad.address.isEmpty() ? ad.address : this.address;
        String newName = !ad.name.isEmpty() ? ad.name : this.name;
        String newTail3 = !ad.vinTail3.isEmpty() ? ad.vinTail3 : this.vinTail3;
        String newTail2 = !ad.vinTail2.isEmpty() ? ad.vinTail2 : this.vinTail2;
        if (newTail2.isEmpty() && newTail3.length() >= 2) {
            newTail2 = newTail3.substring(newTail3.length() - 2);
        }
        String newSig = !ad.mfgSignature.isEmpty() ? ad.mfgSignature : this.mfgSignature;
        if (newAddr.equalsIgnoreCase(this.address)
                && newName.equalsIgnoreCase(this.name)
                && newTail2.equalsIgnoreCase(this.vinTail2)
                && newTail3.equalsIgnoreCase(this.vinTail3)
                && newSig.equalsIgnoreCase(this.mfgSignature)) {
            return this;
        }
        return new VehicleIdentity(
                newAddr,
                newName,
                newTail2,
                newTail3,
                newSig,
                System.currentTimeMillis()
        );
    }

    VehicleIdentity withAddress(String newAddress) {
        if (newAddress == null || newAddress.isEmpty() || newAddress.equalsIgnoreCase(address)) {
            return this;
        }
        return new VehicleIdentity(
                newAddress,
                name,
                vinTail2,
                vinTail3,
                mfgSignature,
                System.currentTimeMillis()
        );
    }

    String displayName() {
        if (!name.isEmpty()) return name;
        if (!vinTail2.isEmpty()) return "LynkCo??" + vinTail2;
        if (!address.isEmpty()) return address;
        return "未知车辆";
    }

    String displayDetail() {
        StringBuilder sb = new StringBuilder(displayName());
        if (!address.isEmpty()) sb.append(" · ").append(address);
        if (!vinTail3.isEmpty()) sb.append(" · VIN尾号 ").append(vinTail3);
        return sb.toString();
    }

    String stableKey() {
        return address + "|" + name + "|" + vinTail2 + "|" + mfgSignature;
    }
}
