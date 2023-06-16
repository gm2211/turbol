export default class GeoUtils {
    static calculateBearing(startLat: number, startLong: number, destLat: number, destLong: number): number {
        const startLatRad: number = this.radians(startLat);
        const startLongRad: number = this.radians(startLong);
        const destLatRad: number = this.radians(destLat);
        const destLongRad: number = this.radians(destLong);
        const differenceInLong: number = destLongRad - startLongRad;
        const verticalChange = Math.cos(startLatRad) * Math.sin(destLatRad);
        const horizontalChange = Math.sin(startLatRad) * Math.cos(destLatRad) * Math.cos(differenceInLong);

        const y: number = Math.sin(differenceInLong) * Math.cos(destLatRad);
        const x: number = verticalChange - horizontalChange;

        const bearingRad: number = Math.atan2(y, x);
        const bearingDeg: number = this.degrees(bearingRad);

        return (bearingDeg + 360) % 360;
    }

    static radians(degrees: number): number {
        return degrees * Math.PI / 180;
    };

    static degrees(radians: number): number {
        return radians * 180 / Math.PI;
    };
}