export default class StringsUtils {
    static editDistance(str1: string, str2: string): number {
        const str1Len: number = str1.length;
        const str2Len: number = str2.length;

        // Create a 2D array to store the minimum edit distances for substrings of s1 and s2
        const distances: number[][] = new Array(str1Len + 1)
            .fill(null)
            .map(() => new Array(str2Len + 1).fill(0));

        // Initialize the base case values
        for (let i = 0; i <= str1Len; i++) {
            distances[i][0] = i;
        }
        for (let j = 0; j <= str2Len; j++) {
            distances[0][j] = j;
        }

        // Compute the minimum edit distance for all substrings of s1 and s2
        for (let i = 1; i <= str1Len; i++) {
            for (let j = 1; j <= str2Len; j++) {
                if (str1.charAt(i - 1) === str2.charAt(j - 1)) {
                    distances[i][j] = distances[i - 1][j - 1];
                } else {
                    distances[i][j] = Math.min(
                        distances[i - 1][j],     // Deletion
                        distances[i][j - 1],     // Insertion
                        distances[i - 1][j - 1]  // Substitution
                    ) + 1;
                }
            }
        }

        return distances[str1Len][str2Len];
    }
}