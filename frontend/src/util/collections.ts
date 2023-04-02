export default class CollectionsUtils {
  static sortBy<T>(values: T[], scoringFunction: (value: T) => number): T[] {
    const sortScoreByValue: Map<T, number> = values
      .map((value: T) => {
        return {
          value: value,
          score: scoringFunction(value)
        }
      })
      .reduce((map, obj) => {
        map.set(obj.value, obj.score)
        return map
      }, new Map<T, number>())
    return values.sort((a, b) => {
      return sortScoreByValue.get(a)! - sortScoreByValue.get(b)!
    })
  }
}
declare global {
  interface Array<T> {
    zipWithIndex(): [T, number][]

    sum(): number | undefined
  }
}
Array.prototype.zipWithIndex = <T>(): [T, number][] => {
  let index = 0
  return (this || []).map((value: T) => [value, index++])
}
Array.prototype.sum = (): number => {
  return (this || []).reduce((sum: number, value: number) => sum + value, 0)
}
