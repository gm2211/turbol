export default class MathUtils {
    static randomInt(max: number | undefined = undefined): number {
        if (max === undefined) {
           max = Math.pow(10, 10)
        }
        return Math.floor(Math.random() * max)
    }
}